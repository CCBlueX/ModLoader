package net.ccbluex.modloader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.ccbluex.modloader.mixin.MixinManager;

/**
 * Loads one mod. Sees the game through its parent and the other loaded mods as siblings.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class ModClassLoader extends URLClassLoader {

    static {
        // Siblings look classes up in each other; one lock per loader would let two of them deadlock.
        registerAsParallelCapable();
    }

    private final Path jar;
    private final Collection<Mod> mods;
    private final MixinManager mixins;

    ModClassLoader(String id, Path jar, Collection<Mod> mods, MixinManager mixins) throws IOException {
        super(id, new URL[] {jar.toUri().toURL()}, ModLoader.class.getClassLoader());
        this.jar = jar;
        this.mods = mods;
        this.mixins = mixins;
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
        return findOwnClass(name);
    }

    @Override
    protected URL findResource(String moduleName, String name) {
        return findResource(name);
    }

    public static boolean isModFirst(String name) {
        return name.startsWith("kotlin/") || name.startsWith("kotlinx/");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> kotlin = isModFirst(name.replace('.', '/')) ? findModClass(name) : null;
        return kotlin != null ? kotlin
                : ScopedValue.where(SharedClassLoader.MOD_LOOKUP, true).call(() -> super.loadClass(name, resolve));
    }

    /** Then the other loaded mods' own resources, which a framework with one class loader for all mods shows too. */
    @Override
    public URL getResource(String name) {
        URL kotlin = isModFirst(name) ? findModResource(name) : null;
        if (kotlin != null) {
            return kotlin;
        }
        URL found = ScopedValue.where(SharedClassLoader.MOD_LOOKUP, true).call(() -> super.getResource(name));
        return found != null ? found : findModResource(name);
    }

    private URL findModResource(String name) {
        URL found = findResource(name);
        for (Mod mod : mods) {
            if (found == null && mod.classLoader != this) {
                found = mod.classLoader.findResource(name);
            }
        }
        return found;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> found = Collections.list(
                ScopedValue.where(SharedClassLoader.MOD_LOOKUP, true).call(() -> super.getResources(name)));
        for (Mod mod : mods) {
            if (mod.classLoader != this) {
                found.addAll(Collections.list(mod.classLoader.findResources(name)));
            }
        }
        return Collections.enumeration(found);
    }

    /**
     * Also closes the jar files that reading this loader's {@code jar:} URLs opened. The JDK keeps one per path for
     * good, and it would hand a rebuilt jar's next load the old content.
     */
    @Override
    public void close() throws IOException {
        for (URL url : getURLs()) {
            if (URI.create("jar:" + url + "!/").toURL().openConnection() instanceof JarURLConnection connection) {
                connection.getJarFile().close();
            }
        }
        super.close();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> found = findModClass(name);
        if (found == null) {
            throw new ClassNotFoundException(name);
        }
        return found;
    }

    private Class<?> findModClass(String name) {
        Class<?> found = findOwnClass(name);
        for (Mod mod : mods) {
            if (found == null && mod.classLoader != this) {
                found = mod.classLoader.findOwnClass(name);
            }
        }
        return found;
    }

    /** A class from the jars of this mod, null when they have none by that name. */
    Class<?> findOwnClass(String name) {
        URL resource = findResource(name.replace('.', '/') + ".class");
        if (resource == null) {
            return null;
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            try (InputStream in = resource.openStream()) {
                byte[] bytes = mixins.rewrite(this, in.readAllBytes());
                return defineClass(name, bytes, 0, bytes.length, new CodeSource(jar.toUri().toURL(), (Certificate[]) null));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
