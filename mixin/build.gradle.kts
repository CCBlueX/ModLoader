dependencies {
    // Comes from the loader's class loader at runtime, like Gson and Guava from the game's.
    compileOnly(project(":loader"))
    compileOnly(libs.gson)
    compileOnly(libs.guava)

    implementation(libs.bundles.mixin) { isTransitive = false }

    testImplementation(project(":loader"))
    testRuntimeOnly(libs.gson)
    testRuntimeOnly(libs.guava)
}
