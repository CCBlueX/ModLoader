import java.time.Duration

subprojects {
    apply(plugin = "java-library")

    group = "net.ccbluex"
    version = "0.1.0"

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
    }

    extensions.configure<TestingExtension> {
        suites.withType<JvmTestSuite>().configureEach { useJUnitJupiter() }
    }

    tasks.withType<Test>().configureEach {
        maxHeapSize = "256m"
        // Tests start a JVM to load mods into; a hung one must not hold a runner.
        timeout = Duration.ofMinutes(5)
    }
}
