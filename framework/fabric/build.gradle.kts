dependencies {
    implementation(project(":loader"))
    compileOnly(libs.fabric.loader) { isTransitive = false }
    compileOnly(libs.gson)
}
