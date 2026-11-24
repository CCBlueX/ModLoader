package net.ccbluex.modloader.fabric;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModFile;
import net.fabricmc.loader.api.FabricLoader;

/** @agent claude-fable-5-2 */
public final class Fabric implements Framework {

    @Override
    public String name() {
        return "Fabric";
    }

    @Override
    public boolean isPresent() {
        return Framework.hasClass("net.fabricmc.loader.api.FabricLoader");
    }

    @Override
    public boolean isLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    @Override
    public ModFile read(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry metadata = file.getJarEntry("fabric.mod.json");
            if (metadata == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(file.getInputStream(metadata), StandardCharsets.UTF_8)) {
                return new FabricMod(this, jar, JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
    }
}
