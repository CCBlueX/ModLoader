package net.ccbluex.modloader.fabric;

import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.ccbluex.modloader.SharedClassLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.LoaderUtil;

/**
 * Fabric's class loader. It asks its parent for what the game and its mods lack, and takes a class from the parent
 * only when the jar it is in is one it was told about at startup. Neither has a way in afterwards, so this goes
 * through its private members.
 *
 * @agent claude-opus-5-5
 */
final class Knot {

    private Knot() {
    }

    static void share(UnaryOperator<ClassLoader> parent) {
        try {
            ClassLoader knot = FabricLauncherBase.getLauncher().getTargetClassLoader();
            Object delegate = field(knot, "delegate").get(knot);
            Field original = field(knot, "originalLoader");
            ClassLoader current = (ClassLoader) original.get(knot);
            // A stand-in of an earlier loader goes; its parent is Knot's own.
            if (current.getClass().getName().equals(SharedClassLoader.class.getName())) {
                current = current.getParent();
            }
            ClassLoader shared = parent.apply(current);
            original.set(knot, shared);
            field(delegate, "parentClassLoader").set(delegate, shared);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lets Knot take classes from these jars through its parent, or no longer when {@code isShown} is false. */
    static void show(URL[] jars, boolean isShown) {
        try {
            ClassLoader knot = FabricLauncherBase.getLauncher().getTargetClassLoader();
            Object delegate = field(knot, "delegate").get(knot);
            Field sources = field(delegate, "validParentCodeSources");
            @SuppressWarnings("unchecked")
            Set<Path> current = (Set<Path>) sources.get(delegate);
            if (current == null) {
                // Without the list Knot takes any jar that is not its own.
                return;
            }
            // Knot reads the set without a lock, so it gets a new one.
            Set<Path> changed = new HashSet<>(current);
            for (URL jar : jars) {
                Path path = LoaderUtil.normalizeExistingPath(Path.of(jar.toURI()));
                if (isShown) {
                    changed.add(path);
                } else {
                    changed.remove(path);
                }
            }
            sources.set(delegate, changed);
        } catch (ReflectiveOperationException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
