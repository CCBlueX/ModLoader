package net.ccbluex.modloader.quilt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModFile;
import net.ccbluex.modloader.SharedClassLoader;
import net.ccbluex.modloader.fabric.Fabric;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;

/**
 * Quilt Loader, which runs Fabric mods next to its own with a class loader, mod list and entrypoints of its own.
 *
 * @agent claude-opus-5-5
 */
public final class Quilt implements Framework {

    private final Fabric fabric = new Fabric();

    @Override
    public String name() {
        return "Quilt";
    }

    @Override
    public boolean isPresent() {
        return Framework.hasClass("org.quiltmc.loader.api.QuiltLoader");
    }

    @Override
    public boolean isLoaded(String id) {
        return QuiltLoader.isModLoaded(id);
    }

    /** The bytes Knot read from Quilt's transform cache, where access wideners and game patches are applied. */
    @Override
    public byte[] preMixinClass(String name) {
        try {
            return QuiltLauncherBase.getLauncher().getClassByteArray(name, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Knot asks this parent for every class and resource it lacks; the field is final and has no way in. */
    @Override
    public void share(UnaryOperator<ClassLoader> parent) {
        try {
            ClassLoader knot = QuiltLauncherBase.getLauncher().getTargetClassLoader();
            Field original = knot.getClass().getDeclaredField("originalLoader");
            original.setAccessible(true);
            ClassLoader current = (ClassLoader) original.get(knot);
            // A stand-in of an earlier loader goes; its parent is Knot's own.
            if (current.getClass().getName().equals(SharedClassLoader.class.getName())) {
                current = current.getParent();
            }
            original.set(knot, parent.apply(current));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fabric mods only; a quilt.mod.json is not read. */
    @Override
    public ModFile read(Path jar) throws IOException {
        ModFile file = fabric.read(jar);
        return file == null ? null : new QuiltMod(this, file);
    }
}
