package net.ccbluex.modloader.mixin;

import java.util.function.Function;

/**
 * Defines the companions of one compilation and the classes Mixin makes up for them. Their code calls into
 * the mods, so it sees every loaded mod.
 *
 * @agent claude-fable-5-2
 */
final class GeneratedClassLoader extends ClassLoader {

    private final MixinCompiler compiler;
    private final Function<String, Class<?>> modClasses;
    private final ClassLoader support;

    /**
     * @param modClasses a class of a loaded mod by name, null when no mod has it
     * @param support Mixin's own classes, for a game without Mixin
     */
    GeneratedClassLoader(MixinCompiler compiler, Function<String, Class<?>> modClasses, ClassLoader support) {
        super("modloader-generated", MixinManager.class.getClassLoader());
        this.compiler = compiler;
        this.modClasses = modClasses;
        this.support = support;
    }

    Class<?> define(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith("org.spongepowered.asm.synthetic.")) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            return loaded != null ? loaded : findClass(name);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> modClass = modClasses.apply(name);
        if (modClass != null) {
            return modClass;
        }
        byte[] generated = compiler.generate(name);
        if (generated != null) {
            return define(name, compiler.rewrite(this, generated));
        }
        return support.loadClass(name);
    }
}
