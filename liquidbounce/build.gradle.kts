plugins {
    `maven-publish`
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(libs.fabric.loader) { isTransitive = false }
}

tasks.jar {
    archiveBaseName = "modloader-liquidbounce"
    archiveVersion = ""
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "modloader-liquidbounce"
        }
    }
}
