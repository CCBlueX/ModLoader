plugins {
    `maven-publish`
}

java {
    withSourcesJar()
}

// Published next to modloader.jar for mods to compile against.
tasks.jar {
    archiveBaseName = "modloader-api"
    archiveVersion = ""
}

val mavenUrl = providers.gradleProperty("mavenUrl")

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "modloader-api"
            pom {
                name = "ModLoader API"
                url = "https://github.com/CCBlueX/ModLoader"
                licenses {
                    license {
                        name = "GNU General Public License v3.0 or later"
                        url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                    }
                }
            }
        }
    }
    // `publish` uploads to -PmavenUrl with -PmavenUsername and -PmavenPassword
    repositories {
        if (mavenUrl.isPresent) {
            maven {
                name = "maven"
                url = uri(mavenUrl.get())
                credentials(PasswordCredentials::class)
            }
        }
    }
}
