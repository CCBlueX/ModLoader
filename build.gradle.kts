import java.time.Duration
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.errorprone) apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "net.ltgt.errorprone")

    group = "net.ccbluex"
    version = "0.1.0-SNAPSHOT"

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
}
