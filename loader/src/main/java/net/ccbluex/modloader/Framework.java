package net.ccbluex.modloader;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A mod framework: the format of its mod jars and, when the running game has it, its list of loaded mods.
 * Implementations are listed in {@code META-INF/services/net.ccbluex.modloader.Framework}.
 *
 * @agent claude-fable-5-2
 */
public interface Framework {

    String name();

    /** Whether the running game has this framework. */
    boolean isPresent();

    /** Only called when {@link #isPresent} holds. */
    boolean isLoaded(String id);

    /** Null when the jar has no metadata of this framework. */
    ModFile read(Path jar) throws IOException;

    /** Whether the game or the framework it runs has this class. */
    static boolean hasClass(String name) {
        try {
            Class.forName(name, false, Framework.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | RuntimeException e) {
            // Forge throws a RuntimeException for client classes on a dedicated server.
            return false;
        }
    }
}
