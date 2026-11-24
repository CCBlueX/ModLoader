// What runs inside the game; the agent unpacks it from lib/ of the jar.
val runtime: Configuration = configurations.create("runtime")

dependencies {
    runtime(project(":loader"))
    runtime(project(":fabric"))
    runtime(project(":neoforge"))
    runtime(project(":forge"))

    // The game the tests load mods into has Gson, as Minecraft does.
    testRuntimeOnly(libs.gson)
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
    from(runtime) { into("lib/runtime") }
}

tasks.test {
    val example = project(":example").tasks.named<Jar>("jar")
    val api = project(":api").tasks.named<Jar>("jar")
    dependsOn(tasks.jar, example, api)
    systemProperty("modloader.jar", tasks.jar.get().archiveFile.get().asFile.path)
    systemProperty("example.jar", example.get().archiveFile.get().asFile.path)
    systemProperty("api.jar", api.get().archiveFile.get().asFile.path)
}
