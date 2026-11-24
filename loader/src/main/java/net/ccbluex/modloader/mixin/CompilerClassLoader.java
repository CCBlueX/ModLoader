package net.ccbluex.modloader.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the compiler with the Mixin and ASM shipped with it, whatever versions the game has, and reads the
 * mods' mixin classes and configs. Mixin keeps its state in statics, so every compilation gets a new one.
 * A class of the game that extends a type of Mixin, such as the config plugin of a mod the game loaded, is
 * defined here again from the game's class file, so that it is one of this Mixin.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class CompilerClassLoader extends URLClassLoader {

    private final Set<String> pluginJars = ConcurrentHashMap.newKeySet();
    private final Set<String> redefinedPackages = ConcurrentHashMap.newKeySet();

    CompilerClassLoader(URL[] urls) {
        super("modloader-mixin", urls, MixinManager.class.getClassLoader());
    }

    /** Own jars first: the parent may have another Mixin. */
    @Override
    protected Class<?> loadClass(String name, boolean link) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && !name.startsWith("java.")) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException e) {
                    // not ours
                }
            }
            if (loaded == null) {
                return super.loadClass(name, link);
            }
            if (link) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException e) {
            String path = name.replace('.', '/') + ".class";
            URL file = getParent().getResource(path);
            if (file == null) {
                throw e;
            }
            String url = file.toString();
            String jar = url.substring(0, url.length() - path.length());
            String folder = url.substring(0, url.lastIndexOf('/') + 1);
            Class<?> type = getParent().loadClass(name);
            boolean isMixinType = extendsMixin(type);
            if (!isMixinType && type.getClassLoader() != getParent().getParent()) {
                throw e;
            }
            try (InputStream in = file.openStream()) {
                byte[] bytes = in.readAllBytes();
                if (!isMixinType && !pluginJars.contains(jar) && !redefinedPackages.contains(folder) && !usesMixin(bytes)) {
                    throw e;
                }
                if (isMixinType) {
                    pluginJars.add(jar);
                }
                redefinedPackages.add(folder);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException io) {
                throw new ClassNotFoundException(name, io);
            }
        }
    }

    private static boolean usesMixin(byte[] bytes) {
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        return constants.contains("org/objectweb/asm/") || constants.contains("org/spongepowered/") || constants.contains("com/llamalad7/mixinextras/");
    }

    private static boolean extendsMixin(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (type.getName().startsWith("org.spongepowered.") || type.getName().startsWith("com.llamalad7.mixinextras.")) {
            return true;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            if (extendsMixin(implemented)) {
                return true;
            }
        }
        return extendsMixin(type.getSuperclass());
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }

    /** Mixin's services only from the own jars: the game's Mixin lists services written against its classes. */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return name.startsWith("META-INF/services/org.spongepowered.")
                || name.startsWith("META-INF/services/com.llamalad7.mixinextras.")
                ? findResources(name) : super.getResources(name);
    }
}
