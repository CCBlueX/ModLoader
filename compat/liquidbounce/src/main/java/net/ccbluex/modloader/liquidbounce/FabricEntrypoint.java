package net.ccbluex.modloader.liquidbounce;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.api.ModInitializer;

/**
 * Points the client at the LiquidBounce folder of the system property {@code liquidbounce.folder} and has it
 * leave add-ons to the launcher. The client reads both once, when the class that keeps them initializes, so each
 * initializes here while the value it reads is swapped. Runs before any other class of the client does.
 *
 * @agent claude-fable-5-2
 */
public final class FabricEntrypoint implements ModInitializer {

    /** Whether the client uses the folder asked for, or none was asked for. */
    static boolean hasFolder = System.getProperty("liquidbounce.folder") == null;

    @Override
    public void onInitialize() {
        String path = System.getProperty("liquidbounce.folder");
        if (path == null) {
            return;
        }
        File folder = new File(path);
        File parent = folder.getParentFile();
        if (parent == null || !folder.isAbsolute() || !folder.getName().equals("LiquidBounce")) {
            throw new IllegalStateException(
                    "liquidbounce.folder has to be the absolute path of a folder named LiquidBounce: " + path);
        }

        Class<?> minecraftClass = Members.type("net.minecraft.client.Minecraft");
        Method getInstance = Members.method(minecraftClass, "getInstance");
        Field gameDirectory = Members.field(minecraftClass, "gameDirectory");
        Class<?> configSystem = Members.type(Members.CLIENT + "config.ConfigSystem");
        Field configSystemInstance = Members.field(configSystem, "INSTANCE");
        Method getRootFolder = Members.method(configSystem, "getRootFolder");

        Object minecraft = Members.call(getInstance, null);
        Object realDirectory = Members.get(gameDirectory, minecraft);
        Object rootFolder;
        // ConfigSystem.rootFolder is the folder LiquidBounce of the game directory.
        Members.set(gameDirectory, minecraft, parent);
        try {
            rootFolder = Members.call(getRootFolder, Members.get(configSystemInstance, null));
        } finally {
            Members.set(gameDirectory, minecraft, realDirectory);
        }
        if (!folder.equals(rootFolder)) {
            throw new IllegalStateException("LiquidBounce already uses the folder " + rootFolder);
        }
        hasFolder = true;

        // AddonInstaller.launcherManaged is whether LiquidLauncher started the game.
        String brand = System.setProperty("minecraft.launcher.brand", "LiquidLauncher");
        try {
            Class.forName(Members.CLIENT + "features.addon.AddonInstaller", true, getClass().getClassLoader());
        } catch (ClassNotFoundException _) {
            // A build from before the client installed add-ons.
        } finally {
            if (brand == null) {
                System.clearProperty("minecraft.launcher.brand");
            } else {
                System.setProperty("minecraft.launcher.brand", brand);
            }
        }
    }
}
