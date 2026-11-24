package net.ccbluex.modloader.neoforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModFile;
import net.ccbluex.modloader.TomlModFile;
import net.neoforged.fml.ModList;

/** @agent claude-fable-5-2 */
public final class NeoForge implements Framework {

    @Override
    public String name() {
        return "NeoForge";
    }

    @Override
    public boolean isPresent() {
        return Framework.hasClass("net.neoforged.fml.ModList");
    }

    @Override
    public boolean isLoaded(String id) {
        return ModList.get().isLoaded(id);
    }

    @Override
    public ModFile read(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Map<String, List<Map<String, String>>> toml = TomlModFile.read(file, "META-INF/neoforge.mods.toml");
            return toml == null ? null : new NeoForgeMod(this, jar, toml, TomlModFile.nestedJars(file));
        }
    }
}
