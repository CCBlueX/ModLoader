package net.ccbluex.modloader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * A mod framework: the format of its mod jars and, when the running game has it, its list of loaded mods.
 * Implementations are listed in {@code META-INF/services/net.ccbluex.modloader.Framework}.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public interface Framework {

    String name();

    /** Whether the running game has this framework. */
    boolean isPresent();

    /** Only called when {@link #isPresent} holds. */
    boolean isLoaded(String id);

    /** Null when the jar has no metadata of this framework. */
    ModFile read(Path jar) throws IOException;

    /**
     * The class as the framework hands it to the game's Mixin, before the mixins of its mods; null when it runs no
     * Mixin over the class. Only called when {@link #isPresent} holds.
     */
    default byte[] preMixinClass(String name) {
        return null;
    }

    /**
     * Puts {@code parent.apply(p)} in place of the parent {@code p} of the framework's class loader, so that what
     * the framework loaded finds the classes and services of the mods loaded later. Called when the loader starts;
     * a parent an earlier loader put there gives way to the new one. Only called when {@link #isPresent} holds.
     */
    default void share(UnaryOperator<ClassLoader> parent) {
    }

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
