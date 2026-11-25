package net.ccbluex.modloader;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * A mod framework: the format of its mod jars and, when the running game has it, its list of loaded mods.
 * Implementations are listed in {@code META-INF/services/net.ccbluex.modloader.Framework}.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public interface Framework {

    String name();

    /** Whether the running game has this framework, also when ModLoader brought it in. */
    boolean isPresent();

    /**
     * Whether the game started with this framework. A framework brought in later runs its mods on classes it never
     * prepared for them.
     */
    default boolean hasStartedGame() {
        return isPresent();
    }

    /** Only called when {@link #isPresent} holds. */
    boolean isLoaded(String id);

    /** Null when the jar has no metadata of this framework. */
    @Nullable ModFile read(Path jar) throws IOException;

    /**
     * The class as the framework hands it to the game's Mixin, before the mixins of its mods; null when it runs no
     * Mixin over the class. Only called when {@link #isPresent} holds.
     */
    default byte @Nullable [] preMixinClass(String name) {
        return null;
    }

    /**
     * Puts {@code parent.apply(p)} in place of the parent {@code p} of the framework's class loader, so that what
     * the framework loaded finds the classes and services of the mods loaded later. Called when the loader starts;
     * a parent an earlier loader put there gives way to the new one. Only called when {@link #isPresent} holds.
     */
    default void share(UnaryOperator<ClassLoader> parent) {
    }

    /**
     * Brings the framework into the game when {@code mods}, the mods of one load that are built for it, need it,
     * and returns the jars of the mods that come with it, such as its API, to load before them. Null when they do
     * not need it or it cannot come in. Only called when {@link #hasStartedGame} does not hold; a framework brought
     * in earlier stays and only adds what these mods need on top.
     */
    default @Nullable List<Path> bringIn(List<ModFile> mods, Instrumentation instrumentation) throws IOException {
        return null;
    }

    /**
     * Whether {@link #bringIn} supplies the mod with this id itself, in the version that fits the game, so a jar of
     * it in a load is left out. Only asked when {@link #hasStartedGame} does not hold.
     */
    default boolean bringsIn(String id) {
        return false;
    }

    /**
     * What the framework replaced in the game, for the mixins of mods outside it, which name what it replaced. Only
     * called when {@link #isPresent} holds.
     */
    default List<Replacement> replacements() {
        return List.of();
    }

    /**
     * With {@code in} null, the method {@code replaced} of the class {@code owner}, which the method {@code by} took
     * the place of; else the call {@code replaced} in its method {@code in}, which the call {@code by} took the place
     * of. Classes by internal name, calls as Mixin's INVOKE targets.
     */
    record Replacement(String owner, @Nullable String in, String replaced, String by) {
    }

    /** Whether the game or the framework it runs has this class. */
    static boolean hasClass(String name) {
        try {
            Class.forName(name, false, Framework.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | RuntimeException _) {
            // Forge throws a RuntimeException for client classes on a dedicated server.
            return false;
        }
    }
}
