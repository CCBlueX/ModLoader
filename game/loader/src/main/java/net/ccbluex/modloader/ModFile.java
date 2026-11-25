package net.ccbluex.modloader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A mod jar as its framework describes it.
 *
 * @agent claude-fable-5-2
 */
public abstract class ModFile {

    public final Framework framework;
    public final Path path;
    public final String id;

    protected ModFile(Framework framework, Path path, String id) {
        this.framework = framework;
        this.path = path;
        this.id = id;
    }

    /** Ids of the mods this one cannot run without. */
    public List<String> dependencies() {
        return List.of();
    }

    /** Jar entries of the jars bundled in this one. */
    public List<String> nestedJars() {
        return List.of();
    }

    public List<String> mixinConfigs() {
        return List.of();
    }

    /** Whether {@link #start} would run anything. */
    public abstract boolean hasEntrypoints();

    /**
     * Makes the mod known to the framework before its mixins apply, which may already ask the framework about
     * it, its entrypoints included. Only called when the game has the framework.
     */
    public void register(ModClassLoader loader) throws ReflectiveOperationException, IOException {
    }

    /** Runs what the framework runs for a mod at startup. Only called when the game has the framework. */
    public abstract void start(ModClassLoader loader) throws ReflectiveOperationException;

    /** Takes back what {@link #register} and {@link #start} gave the framework. Safe when neither ran. */
    public void stop() {
    }
}
