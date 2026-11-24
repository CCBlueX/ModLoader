package net.ccbluex.modloader.quilt;

import java.io.IOException;
import java.util.List;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModClassLoader;
import net.ccbluex.modloader.ModFile;
import org.quiltmc.loader.api.plugin.ModContainerExt;

/**
 * A Fabric mod in a Quilt game. Its metadata and start are the Fabric adapter's, which reach Quilt through the
 * Fabric Loader API; only the mod list and entrypoints are Quilt's own.
 *
 * @agent claude-opus-5-5
 */
final class QuiltMod extends ModFile {

    private final ModFile fabric;
    /** Null before {@link #register}. */
    private ModContainerExt container;

    QuiltMod(Framework framework, ModFile fabric) {
        super(framework, fabric.path, fabric.id);
        this.fabric = fabric;
    }

    @Override
    public List<String> dependencies() {
        return fabric.dependencies();
    }

    @Override
    public List<String> nestedJars() {
        return fabric.nestedJars();
    }

    @Override
    public List<String> mixinConfigs() {
        return fabric.mixinConfigs();
    }

    @Override
    public boolean hasEntrypoints() {
        return fabric.hasEntrypoints();
    }

    @Override
    public void register(ModClassLoader loader) throws ReflectiveOperationException, IOException {
        container = ModContainers.add(path, loader);
    }

    @Override
    public void start(ModClassLoader loader) throws ReflectiveOperationException {
        fabric.start(loader);
    }

    @Override
    public void stop() {
        if (container != null) {
            ModContainers.remove(container);
            container = null;
        }
    }
}
