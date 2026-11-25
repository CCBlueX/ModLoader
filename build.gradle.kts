import java.time.Duration
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.errorprone) apply false
}

// A release passes its own: -PmodloaderVersion=0.1.0
version = providers.gradleProperty("modloaderVersion").getOrElse("0.1.0-SNAPSHOT")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "net.ltgt.errorprone")

    group = "net.ccbluex"
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    dependencies {
        // Minecraft's own, like Gson and Guava.
        "compileOnly"(rootProject.libs.jspecify)
        "errorprone"(rootProject.libs.errorprone)
        "errorprone"(rootProject.libs.nullaway)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        // Every package is @NullMarked; of Error Prone only NullAway runs, which holds the code to it.
        options.errorprone {
            disableAllChecks = true
            error("NullAway")
            option("NullAway:OnlyNullMarked", "true")
            option("NullAway:JSpecifyMode", "true")
            // Tests pass null where they please.
            excludedPaths = ".*/src/test/.*"
        }
    }

    extensions.configure<TestingExtension> {
        suites.withType<JvmTestSuite>().configureEach { useJUnitJupiter(rootProject.libs.versions.junit) }
    }

    tasks.withType<Test>().configureEach {
        maxHeapSize = "256m"
        // Tests start a JVM to load mods into; a hung one must not hold a runner.
        timeout = Duration.ofMinutes(5)
    }

    // Shared by every module with a publication `maven`: where it goes, and the POM's name, url and license.
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    // Read late: the module sets its artifactId after this runs.
                    name = provider { artifactId }
                    url = "https://github.com/CCBlueX/ModLoader"
                    licenses {
                        license {
                            name = "GNU General Public License v3.0 or later"
                            url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                        }
                    }
                }
            }
            // `publish` uploads to -PmavenUrl with -PmavenUsername and -PmavenPassword
            val mavenUrl = providers.gradleProperty("mavenUrl")
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
    }
}
