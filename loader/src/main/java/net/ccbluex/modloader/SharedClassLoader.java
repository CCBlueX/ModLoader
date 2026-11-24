package net.ccbluex.modloader;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Stands in for the parent of the framework's class loader: what the parent has, then the classes and resources of
 * the loaded mods' own jars. Libraries the framework loaded look classes and services up through their own class
 * loader, which knows nothing of the mods loaded later. A mod's class comes from the mod's class loader, so there
 * is one copy of it, and the mods' class loaders are never asked to delegate back.
 *
 * @agent claude-opus-5-5
 */
public final class SharedClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    /**
     * Bound while a mod's class loader asks its parents. The JVM keeps every class a loader returned, so the
     * framework's loader would hand the next load of a mod the classes of the one before.
     */
    static final ScopedValue<Boolean> MOD_LOOKUP = ScopedValue.newInstance();

    private final Collection<Mod> mods;

    SharedClassLoader(ClassLoader parent, Collection<Mod> mods) {
        super("modloader-shared", parent);
        this.mods = mods;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!MOD_LOOKUP.isBound()) {
            for (Mod mod : mods) {
                Class<?> found = mod.classLoader.findOwnClass(name);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    protected URL findResource(String name) {
        if (MOD_LOOKUP.isBound()) {
            return null;
        }
        for (Mod mod : mods) {
            URL found = mod.classLoader.findResource(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        List<URL> found = new ArrayList<>();
        if (!MOD_LOOKUP.isBound()) {
            for (Mod mod : mods) {
                found.addAll(Collections.list(mod.classLoader.findResources(name)));
            }
        }
        return Collections.enumeration(found);
    }
}
