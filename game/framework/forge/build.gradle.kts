dependencies {
    implementation(project(":loader"))
    compileOnly(libs.bundles.forge) { isTransitive = false }
    compileOnly(libs.forge.universal) {
        artifact { classifier = "universal" }
        isTransitive = false
    }
}
