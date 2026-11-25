rootProject.name = "ModLoader"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://libraries.minecraft.net/") { name = "Mojang" }
        maven("https://maven.quiltmc.org/repository/release/") { name = "Quilt" }
    }
}

include("api", "example")

// What goes into the game: agent builds modloader.jar, with the others under its lib/.
for (module in listOf("agent", "bridge", "loader", "mixin")) {
    include(module)
    project(":$module").projectDir = file("game/$module")
}

// One module per mod framework; a new one also needs its line in game/agent/build.gradle.kts.
for (framework in listOf("fabric", "quilt", "neoforge", "forge")) {
    include(framework)
    project(":$framework").projectDir = file("game/framework/$framework")
}

// Mods that let existing builds of a client run when a launcher loads them into a running game.
for (client in listOf("liquidbounce")) {
    include(client)
    project(":$client").projectDir = file("compat/$client")
}
