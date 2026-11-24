dependencies {
    api(project(":api"))
    // On the bootstrap class path of the game.
    compileOnly(project(":bridge"))
    compileOnly(libs.gson)
}
