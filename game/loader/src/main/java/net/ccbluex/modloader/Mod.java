package net.ccbluex.modloader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import net.ccbluex.modloader.api.LoadedMod;
import net.ccbluex.modloader.api.RuntimeMod;

/**
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class Mod implements LoadedMod {

    final ModFile file;
    final ModClassLoader classLoader;
    /** The mods that came in this one's jar and go with it. */
    final List<Mod> bundled = new ArrayList<>();
    private final ModLoader loader;
    private List<RuntimeMod> entrypoints = List.of();
    private boolean isStartedByFramework;
    private boolean isUnloaded;

    Mod(ModLoader loader, ModFile file, ModClassLoader classLoader) {
        this.loader = loader;
        this.file = file;
        this.classLoader = classLoader;
    }

    @Override
    public String id() {
        return file.id;
    }

    /**
     * Runs the framework's entrypoints, before any class of the mod loads: a framework may still turn the mod into
     * a module, which no loaded class allows.
     *
     * @return what the entrypoints had to leave out, for the person loading the mod
     */
    List<String> startInFramework() throws ReflectiveOperationException {
        List<String> leftOut = new ArrayList<>();
        if (file.framework.isPresent()) {
            isStartedByFramework = file.hasEntrypoints();
            try {
                file.start(classLoader);
            } catch (RuntimeException e) {
                if (file.framework.hasStartedGame()) {
                    throw e;
                }
                // A framework brought in later never had its start, which its entrypoints may need.
                e.printStackTrace();
                leftOut.add(ModLoader.describe(e));
                for (Throwable other : e.getSuppressed()) {
                    leftOut.add(ModLoader.describe(other));
                }
            }
        }
        return leftOut;
    }

    /** Runs the mod's RuntimeMods, once the framework entrypoints of every mod of the load have run. */
    void start() {
        List<ServiceLoader.Provider<RuntimeMod>> providers = ServiceLoader.load(RuntimeMod.class, classLoader).stream()
                .filter(provider -> provider.type().getClassLoader() == classLoader)
                .toList();
        if (!file.framework.isPresent() && file.hasEntrypoints() && providers.isEmpty()
                && file.mixinConfigs().isEmpty()) {
            throw new IllegalStateException("built for " + file.framework.name()
                    + ", which this game does not have, and has no RuntimeMod entrypoint or mixins");
        }
        entrypoints = providers.stream().map(ServiceLoader.Provider::get).toList();
        entrypoints.forEach(entrypoint -> entrypoint.load(this));
    }

    /** What a framework entrypoint registered with the game stays there, so only a RuntimeMod can undo it. */
    boolean canUnload() {
        return (!isStartedByFramework || !entrypoints.isEmpty()) && bundled.stream().allMatch(Mod::canUnload);
    }

    /** This mod and every mod bundled with it, at any depth. */
    List<Mod> withBundled() {
        List<Mod> all = new ArrayList<>(List.of(this));
        bundled.forEach(mod -> all.addAll(mod.withBundled()));
        return all;
    }

    @Override
    public void unload() {
        if (!isUnloaded) {
            loader.checkUnload(this);
        }
        List<Mod> going = new ArrayList<>();
        collect(going);
        // Before the mods leave the loader: their unload may still load classes from the mods bundled with them.
        going.forEach(mod -> mod.entrypoints.forEach(RuntimeMod::unload));
        loader.remove(this);
        loader.applyMixins();
        going.forEach(Mod::close);
    }

    /** This mod and the ones bundled with it, once; a RuntimeMod may call {@link #unload} from its own. */
    private void collect(List<Mod> going) {
        if (!isUnloaded) {
            isUnloaded = true;
            going.add(this);
            bundled.forEach(mod -> mod.collect(going));
        }
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
}
