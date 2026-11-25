package net.ccbluex.modloader.neoforge;

import com.electronwill.nightconfig.core.Config;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModFile;
import net.ccbluex.modloader.TomlModFile;
import net.neoforged.fml.ModList;

/**
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
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

    /** NeoForge draws the hotbar and what goes with it as layers, and screens through its hooks. */
    @Override
    public List<Replacement> replacements() {
        return List.of(
                new Replacement("net/minecraft/client/gui/Hud", null, "extractHotbarAndDecorations", "extractHotbar"),
                new Replacement("net/minecraft/client/gui/Gui", "extractRenderState",
                        "Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                        "Lnet/neoforged/neoforge/client/ClientHooks;extractScreen(Lnet/minecraft/client/gui/screens/Screen;Ljava/util/Stack;"
                                + "Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"));
    }

    @Override
    public ModFile read(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Config toml = TomlModFile.read(file, "META-INF/neoforge.mods.toml");
            return toml == null ? null : new NeoForgeMod(this, jar, toml, TomlModFile.nestedJars(file));
        }
    }
}
