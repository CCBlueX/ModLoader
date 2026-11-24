package net.ccbluex.modloader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.ServiceLoader;
import net.ccbluex.modloader.api.LoadedMod;
import net.ccbluex.modloader.api.RuntimeMod;

/** @agent claude-fable-5-2 */
final class Mod implements LoadedMod {

    final ModFile file;
    final ModClassLoader classLoader;
    private final ModLoader loader;
    private List<RuntimeMod> entrypoints = List.of();
    private boolean startedByFramework;

    Mod(ModLoader loader, ModFile file, ModClassLoader classLoader) {
        this.loader = loader;
        this.file = file;
        this.classLoader = classLoader;
    }

    @Override
    public String id() {
        return file.id;
    }

    void start() throws ReflectiveOperationException {
        boolean present = file.framework.isPresent();
        if (present) {
            // First, as a framework may still turn the mod into a module, which no loaded class allows.
            startedByFramework = file.hasEntrypoints();
            file.start(classLoader);
        }
        List<ServiceLoader.Provider<RuntimeMod>> providers = ServiceLoader.load(RuntimeMod.class, classLoader).stream()
                .filter(provider -> provider.type().getClassLoader() == classLoader)
                .toList();
        if (!present && file.hasEntrypoints() && providers.isEmpty() && file.mixinConfigs().isEmpty()) {
            throw new IllegalStateException("built for " + file.framework.name()
                    + ", which this game does not have, and has no RuntimeMod entrypoint or mixins");
        }
        entrypoints = providers.stream().map(ServiceLoader.Provider::get).toList();
        entrypoints.forEach(entrypoint -> entrypoint.load(this));
    }

    /** What a framework entrypoint registered with the game stays there, so only a RuntimeMod can undo it. */
    boolean canUnload() {
        return !startedByFramework || !entrypoints.isEmpty();
    }

    @Override
    public void unload() {
        if (!loader.remove(this)) {
            return;
        }
        entrypoints.forEach(RuntimeMod::unload);
        close();
    }

    void close() {
        if (file.framework.isPresent()) {
            file.stop();
        }
        try {
            classLoader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return file.id + " (" + file.framework.name() + ") " + file.path;
    }
}
