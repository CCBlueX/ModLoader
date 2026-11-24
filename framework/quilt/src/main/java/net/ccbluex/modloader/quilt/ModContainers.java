package net.ccbluex.modloader.quilt;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import net.ccbluex.modloader.fabric.ClassLoaderAdapter;
import net.fabricmc.loader.impl.ModContainerImpl;
import org.quiltmc.loader.api.LanguageAdapter;
import org.quiltmc.loader.api.plugin.ModContainerExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.entrypoint.EntrypointStorage;
import org.quiltmc.loader.impl.fabric.metadata.FabricModMetadataReader;
import org.quiltmc.loader.impl.fabric.metadata.ParseMetadataException;
import org.quiltmc.loader.impl.metadata.qmj.ProvidedModContainer;

/**
 * Quilt Loader's list of mods and its entrypoints, which the Fabric Loader API on Quilt reads as well. The loader
 * fills both at startup and has no way in afterwards, so this goes through its private members.
 *
 * @agent claude-opus-5-5
 */
final class ModContainers {

    private ModContainers() {
    }

    /** Adds the mod and every entrypoint it declares, created from {@code classes}. */
    static ModContainerExt add(Path jar, ClassLoader classes) throws IOException {
        ModMetadataExt metadata;
        try (JarFile file = new JarFile(jar.toFile()); InputStream in = file.getInputStream(file.getEntry("fabric.mod.json"))) {
            metadata = FabricModMetadataReader.parseMetadata(in).asQuiltModMetadata();
        } catch (ParseMetadataException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        QuiltLoaderImpl loader = QuiltLoaderImpl.INSTANCE;
        RuntimeModContainer container = new RuntimeModContainer(metadata, jar, classes);
        try {
            Method addMod = QuiltLoaderImpl.class.getDeclaredMethod("addMod", ModContainerExt.class);
            addMod.setAccessible(true);
            addMod.invoke(loader, container);

            // Quilt's adapters for Java and Kotlin would go through Knot; other languages still do.
            Map<String, LanguageAdapter> adapters = new HashMap<>((Map<String, LanguageAdapter>) field("adapterMap").get(loader));
            adapters.put("default", new ClassLoaderAdapter(classes, false));
            adapters.put("kotlin", new ClassLoaderAdapter(classes, true));
            EntrypointStorage storage = (EntrypointStorage) field("entrypointStorage").get(loader);
            for (Map.Entry<String, Collection<ModMetadataExt.ModEntrypoint>> key : metadata.getEntrypoints().entrySet()) {
                for (ModMetadataExt.ModEntrypoint entrypoint : key.getValue()) {
                    storage.add(container, key.getKey(), entrypoint, adapters);
                }
            }
        } catch (Exception e) {
            // Entrypoint storage throws plain exceptions; what got into Quilt's lists comes out again.
            remove(container);
            throw new IllegalStateException("could not add " + metadata.id() + " to Quilt's mods", e);
        }
        return container;
    }

    static void remove(ModContainerExt container) {
        try {
            QuiltLoaderImpl loader = QuiltLoaderImpl.INSTANCE;
            Field provider = ProvidedModContainer.class.getDeclaredField("container");
            provider.setAccessible(true);
            // The map also has the container under every id the mod provides.
            for (Iterator<?> mods = ((Map<?, ?>) field("modMap").get(loader)).values().iterator(); mods.hasNext(); ) {
                Object mod = mods.next();
                if (mod == container || mod instanceof ProvidedModContainer && provider.get(mod) == container) {
                    mods.remove();
                }
            }
            ((List<?>) field("mods").get(loader)).remove(container);

            Object storage = field("entrypointStorage").get(loader);
            Field byKey = EntrypointStorage.class.getDeclaredField("entryMap");
            byKey.setAccessible(true);
            Method owner = Class.forName(EntrypointStorage.class.getName() + "$Entry", false, EntrypointStorage.class.getClassLoader())
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

            // The Fabric Loader API's view of each container, kept for good.
            Field views = ModContainerImpl.class.getDeclaredField("VALUES");
            views.setAccessible(true);
            ((Map<?, ?>) views.get(null)).remove(container);
            ((RuntimeModContainer) container).close();
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = QuiltLoaderImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
