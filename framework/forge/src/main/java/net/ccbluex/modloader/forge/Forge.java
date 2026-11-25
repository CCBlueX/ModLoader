package net.ccbluex.modloader.forge;

import com.electronwill.nightconfig.core.Config;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModFile;
import net.ccbluex.modloader.TomlModFile;
import net.minecraftforge.fml.ModList;

/**
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class Forge implements Framework {

    @Override
    public String name() {
        return "Forge";
    }

    @Override
    public boolean isPresent() {
        return Framework.hasClass("net.minecraftforge.fml.ModList");
    }

    @Override
    public boolean isLoaded(String id) {
        return ModList.isLoaded(id);
    }

    @Override
    public List<Replacement> replacements() {
        Replacement screen = new Replacement("net/minecraft/client/gui/Gui", "extractRenderState", "Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", "Lnet/minecraft/client/gui/Gui;drawScreen(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V");
        if (!Framework.hasClass("net.minecraftforge.client.gui.overlay.ForgeLayeredDraw")) {
            return List.of(screen);
        }
        return List.of(screen, new Replacement("net/minecraft/client/gui/Hud", null, "extractHotbarAndDecorations", "extractItemHotbar"));
    }

    @Override
    public ModFile read(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Config toml = TomlModFile.read(file, "META-INF/mods.toml");
            if (toml == null) {
                return null;
            }
            Manifest manifest = file.getManifest();
            String mixinConfigs = manifest == null ? null : manifest.getMainAttributes().getValue("MixinConfigs");
            return new ForgeMod(this, jar, toml, TomlModFile.nestedJars(file),
                    mixinConfigs == null ? List.of() : List.of(mixinConfigs.split(",")));
        }
    }
}
