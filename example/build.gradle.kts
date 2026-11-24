dependencies {
    compileOnly(project(":api"))
    compileOnly(libs.fabric.loader) { isTransitive = false }
    compileOnly(libs.neoforge.fml)
    compileOnly(libs.bundles.forge) { isTransitive = false }
    compileOnly(libs.forge.universal) {
        artifact { classifier = "universal" }
        isTransitive = false
    }
}
