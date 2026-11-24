package net.ccbluex.modloader;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads one mod. Sees the game through its parent and the other loaded mods as siblings.
 *
 * @agent claude-fable-5-2
 */
public final class ModClassLoader extends URLClassLoader {

    private final Path jar;
    private final Collection<Mod> mods;

    ModClassLoader(String id, Path jar, Collection<Mod> mods) throws IOException {
        super(id, new URL[] {jar.toUri().toURL()}, ModLoader.class.getClassLoader());
        this.jar = jar;
        this.mods = mods;
    }

    void add(URL library) {
        addURL(library);
    }

    /**
     * Makes the mod's packages a module in a new layer below {@code parent}, for frameworks that look mods up
     * by module. Has to happen before the first class of the mod loads.
     */
    public ModuleLayer defineModule(String name, ModuleLayer parent) throws IOException {
        ModuleDescriptor.Builder descriptor = ModuleDescriptor.newAutomaticModule(name);
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : (Iterable<JarEntry>) file.stream()::iterator) {
                String path = entry.getName();
                if (path.endsWith(".class") && path.contains("/") && !path.startsWith("META-INF/")) {
                    descriptor.packages(Set.of(path.substring(0, path.lastIndexOf('/')).replace('/', '.')));
                } else if (path.startsWith("META-INF/services/") && !entry.isDirectory()) {
                    // A module's services are only found when its descriptor names them.
                    List<String> providers = new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8).lines()
                            .map(line -> line.replaceAll("#.*", "").strip())
                            .filter(line -> !line.isEmpty())
                            .toList();
                    if (!providers.isEmpty()) {
                        descriptor.provides(path.substring("META-INF/services/".length()), providers);
                    }
                }
            }
        }
        ModuleReference module = new ModuleReference(descriptor.build(), jar.toUri()) {
            @Override
            public ModuleReader open() {
                // Only the JDK's own class loaders read modules through this.
                throw new UnsupportedOperationException();
            }
        };
        ModuleFinder finder = new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String wanted) {
                return wanted.equals(name) ? Optional.of(module) : Optional.empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.of(module);
            }
        };
        Configuration configuration = parent.configuration().resolve(finder, ModuleFinder.of(), Set.of(name));
        return parent.defineModules(configuration, _ -> this);
    }

    /** Classes and resources of a named module are asked for by module name; there is one module per loader. */
    @Override
    protected Class<?> findClass(String moduleName, String name) {
        try {
            return findOwnClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    protected URL findResource(String moduleName, String name) {
        return findResource(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return findOwnClass(name);
        } catch (ClassNotFoundException e) {
            for (Mod mod : mods) {
                if (mod.classLoader != this) {
                    try {
                        return mod.classLoader.findOwnClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // not in this sibling
                    }
                }
            }
            throw e;
        }
    }

    private Class<?> findOwnClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            return loaded != null ? loaded : super.findClass(name);
        }
    }
}
