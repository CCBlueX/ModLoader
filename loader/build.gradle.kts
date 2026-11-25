dependencies {
    api(project(":api"))
    // On the bootstrap class path of the game.
    compileOnly(project(":bridge"))
    // The game's own.
    compileOnly(libs.gson)
    compileOnly(libs.guava)
    api(libs.nightconfig.toml)
}
