plugins {
    `maven-publish`
}

// What runs inside the game. The agent unpacks it from lib/ of the jar, one directory per class loader.
val boot: Configuration = configurations.create("boot")
val runtime: Configuration = configurations.create("runtime")
val mixin: Configuration = configurations.create("mixin")

// The mod the tests load, with mixins into the stand-in game of the test sources. It compiles against the
// widened classes first, as a mod with an access widener does.
val widened: SourceSet = sourceSets.create("widened")
val mod: SourceSet = sourceSets.create("mod") {
    compileClasspath += widened.output + sourceSets.test.get().output
}

dependencies {
    boot(project(":bridge"))

    runtime(project(":loader"))
    runtime(project(":fabric"))
    runtime(project(":quilt"))
    runtime(project(":neoforge"))
    runtime(project(":forge"))

    mixin(project(":mixin"))

    "modCompileOnly"(project(":api"))
    "modCompileOnly"(libs.mixin)

    // The game the tests load mods into has Gson and Guava, as Minecraft does.
    testRuntimeOnly(libs.gson)
    testRuntimeOnly(libs.guava)
    testCompileOnly(libs.jspecify)
}

tasks.jar {
    archiveBaseName = "modloader"
    archiveVersion = ""
    manifest {
        attributes(
            "Agent-Class" to "net.ccbluex.modloader.agent.Agent",
            "Can-Redefine-Classes" to true,
            "Can-Retransform-Classes" to true,
            "Implementation-Version" to project.version,
        )
    }
    from(boot) { into("lib/boot") }
    from(runtime) { into("lib/runtime") }
    from(mixin) { into("lib/mixin") }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // Only the jar, so the POM lists no dependencies: they are inside it.
            artifact(tasks.jar)
            artifactId = "modloader"
        }
    }
}

val modJar = tasks.register<Jar>("modJar") {
    archiveBaseName = "mixed"
    from(mod.output)
}

// The command line tool of the Rust crate in attach/, which looks for modloader.jar beside itself.
val cli = tasks.register<Exec>("cli") {
    workingDir = rootProject.file("attach")
    commandLine("cargo", "build", "--release", "--locked", "--features", "cli")
}

val dist = tasks.register<Sync>("dist") {
    dependsOn(cli)
    from(tasks.jar)
    from(rootProject.file("attach/target/release")) { include("modloader", "modloader.exe") }
    into(layout.buildDirectory.dir("dist"))
}

tasks.assemble {
    dependsOn(dist)
}

tasks.test {
    val example = project(":example").tasks.named<Jar>("jar")
    val api = project(":api").tasks.named<Jar>("jar")
    // Inputs, not only dependencies: the paths stay the same when the code in the files changes.
    inputs.files(dist, example, api, modJar)
    // Windows adds the .exe itself.
    systemProperty("modloader.cli", dist.get().destinationDir.resolve("modloader").path)
    systemProperty("example.jar", example.get().archiveFile.get().asFile.path)
    systemProperty("api.jar", api.get().archiveFile.get().asFile.path)
    systemProperty("mixed.jar", modJar.get().archiveFile.get().asFile.path)
}
