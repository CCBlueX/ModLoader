plugins {
    `maven-publish`
}

java {
    withSourcesJar()
}

dependencies {
    // Mods compiling against the API see its nullness annotations.
    compileOnlyApi(libs.jspecify)
}

// Published next to modloader.jar for mods to compile against.
tasks.jar {
    archiveBaseName = "modloader-api"
    archiveVersion = ""
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "modloader-api"
        }
    }
}
