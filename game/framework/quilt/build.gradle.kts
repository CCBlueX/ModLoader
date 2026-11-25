dependencies {
    implementation(project(":loader"))
    // Fabric mods read and start as on Fabric, through the Fabric Loader API Quilt Loader implements.
    implementation(project(":fabric"))
    compileOnly(libs.quilt.loader) { isTransitive = false }
}
