dependencies {
    compileOnly(project(":api"))
    compileOnly(libs.mixin) { isTransitive = false }
    compileOnly(libs.fabric.loader) { isTransitive = false }
    compileOnly(libs.neoforge.fml)
    compileOnly(libs.bundles.forge) { isTransitive = false }
    compileOnly(libs.forge.universal) {
        artifact { classifier = "universal" }
        isTransitive = false
    }
}

tasks.jar {
    // Where Forge looks for a mod's mixin configs.
    manifest { attributes("MixinConfigs" to "example.mixins.json") }
}
