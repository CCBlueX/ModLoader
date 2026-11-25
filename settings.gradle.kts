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

include("api", "bridge", "loader", "mixin", "agent", "example", "liquidbounce")

// One module per mod framework; a new one also needs its line in agent/build.gradle.kts.
for (framework in listOf("fabric", "quilt", "neoforge", "forge")) {
    include(framework)
    project(":$framework").projectDir = file("framework/$framework")
}
