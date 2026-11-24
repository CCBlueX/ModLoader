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
            "Main-Class" to "net.ccbluex.modloader.cli.Main",
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

val modJar = tasks.register<Jar>("modJar") {
    archiveBaseName = "mixed"
    from(mod.output)
}

tasks.test {
    val example = project(":example").tasks.named<Jar>("jar")
    val api = project(":api").tasks.named<Jar>("jar")
    // Inputs, not only dependencies: the paths stay the same when the code in the jars changes.
    inputs.files(tasks.jar, example, api, modJar)
    systemProperty("modloader.jar", tasks.jar.get().archiveFile.get().asFile.path)
    systemProperty("example.jar", example.get().archiveFile.get().asFile.path)
    systemProperty("api.jar", api.get().archiveFile.get().asFile.path)
    systemProperty("mixed.jar", modJar.get().archiveFile.get().asFile.path)
}
