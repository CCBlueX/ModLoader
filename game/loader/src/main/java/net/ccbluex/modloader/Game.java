package net.ccbluex.modloader;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * What the running game says about itself, asked through its classes by name.
 *
 * @agent claude-fable-5-2
 */
public final class Game {

    private Game() {
    }

    /** A dedicated server has no {@code Minecraft} class. */
    public static boolean isClient() {
        return Framework.hasClass("net.minecraft.client.Minecraft");
    }

    /** Minecraft's own version, such as {@code 26.3}. */
    public static String version() throws ReflectiveOperationException {
        ClassLoader game = Game.class.getClassLoader();
        Object current = Class.forName("net.minecraft.SharedConstants", false, game)
                .getMethod("getCurrentVersion")
                .invoke(null);
        return (String) Class.forName("net.minecraft.WorldVersion", false, game).getMethod("id").invoke(current);
    }

    public static Path directory() throws ReflectiveOperationException {
        if (!isClient()) {
            // A dedicated server runs in its directory.
            return Path.of("").toAbsolutePath();
        }
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, Game.class.getClassLoader());
        Field directory = minecraft.getField("gameDirectory");
        return ((File) directory.get(minecraft.getMethod("getInstance").invoke(null))).toPath().toAbsolutePath();
    }
}
