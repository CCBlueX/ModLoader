package net.ccbluex.modloader.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.ParseMetadataException;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

/**
 * Fabric Loader's list of mods, which a mod asks for its own container, version and files, and its entrypoints,
 * which mods ask for the entrypoints of a key. The loader fills both at startup and has no way in afterwards, so
 * this goes through its private members; a loader that renames them fails the load with the name it misses.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class ModContainers {

    private ModContainers() {
    }

    /** Adds the mod and every entrypoint it declares, created from {@code classes}. */
    static void add(Path jar, ClassLoader classes) throws ReflectiveOperationException, IOException {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        LoaderModMetadata metadata;
        try (JarFile file = new JarFile(jar.toFile()); InputStream in = file.getInputStream(file.getEntry("fabric.mod.json"))) {
            metadata = ModMetadataParser.parseMetadata(in, jar.toString(), List.of(), new VersionOverrides(),
                    new DependencyOverrides(loader.getConfigDir()), loader.isDevelopmentEnvironment());
        } catch (ParseMetadataException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        Method createPlain = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
        createPlain.setAccessible(true);
        Method addMod = FabricLoaderImpl.class.getDeclaredMethod("addMod", ModCandidateImpl.class);
        addMod.setAccessible(true);
        addMod.invoke(loader, createPlain.invoke(null, List.of(jar), metadata, false, List.of()));

        Object container = loader.getModContainer(metadata.getId()).orElseThrow();
        Object storage = entrypoints(loader);
        Method addEntrypoint = storage.getClass().getDeclaredMethod("add", ModContainerImpl.class, String.class, EntrypointMetadata.class, Map.class);
        addEntrypoint.setAccessible(true);
        // Fabric's Java and Kotlin adapters would go through Knot; other languages still do.
        Field adapterMap = FabricLoaderImpl.class.getDeclaredField("adapterMap");
        adapterMap.setAccessible(true);
        Map<String, LanguageAdapter> adapters = new HashMap<>((Map<String, LanguageAdapter>) adapterMap.get(loader));
        adapters.put("default", new ClassLoaderAdapter(classes, false));
        adapters.put("kotlin", new ClassLoaderAdapter(classes, true));
        for (String key : metadata.getEntrypointKeys()) {
            for (EntrypointMetadata entrypoint : metadata.getEntrypoints(key)) {
                addEntrypoint.invoke(storage, container, key, entrypoint, adapters);
            }
        }
    }

    static void remove(String id) {
        try {
            Field byId = FabricLoaderImpl.class.getDeclaredField("modMap");
            Field all = FabricLoaderImpl.class.getDeclaredField("mods");
            byId.setAccessible(true);
            all.setAccessible(true);
            Object container = ((Map<?, ?>) byId.get(FabricLoaderImpl.INSTANCE)).get(id);
            if (container == null) {
                return;
            }
            // The map also has the container under every id the mod provides.
            ((Map<?, ?>) byId.get(FabricLoaderImpl.INSTANCE)).values().removeIf(other -> other == container);
            ((List<?>) all.get(FabricLoaderImpl.INSTANCE)).remove(container);

            Object storage = entrypoints(FabricLoaderImpl.INSTANCE);
            Field byKey = storage.getClass().getDeclaredField("entryMap");
            byKey.setAccessible(true);
            Method owner = Class.forName(storage.getClass().getName() + "$Entry", false, storage.getClass().getClassLoader())
                    .getDeclaredMethod("getModContainer");
            owner.setAccessible(true);
            Map<?, ? extends List<?>> entries = (Map<?, ? extends List<?>>) byKey.get(storage);
            for (List<?> entrypoints : entries.values()) {
                for (Object entrypoint : List.copyOf(entrypoints)) {
                    if (owner.invoke(entrypoint) == container) {
                        entrypoints.remove(entrypoint);
                    }
                }
            }
            // A key without entries counts as one some mod has.
            entries.values().removeIf(List::isEmpty);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object entrypoints(FabricLoaderImpl loader) throws ReflectiveOperationException {
        Field storage = FabricLoaderImpl.class.getDeclaredField("entrypointStorage");
        storage.setAccessible(true);
        return storage.get(loader);
    }
}
