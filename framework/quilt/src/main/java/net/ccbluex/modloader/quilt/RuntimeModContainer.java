package net.ccbluex.modloader.quilt;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import org.quiltmc.loader.api.plugin.ModContainerExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt;

/**
 * A loaded Fabric mod in Quilt's mod list. Its classes come from its own class loader, so Quilt's class path never
 * gets it.
 *
 * @agent claude-opus-5-5
 */
final class RuntimeModContainer implements ModContainerExt {

    private final ModMetadataExt metadata;
    private final Path jar;
    private final Path root;
    private final ClassLoader classes;

    RuntimeModContainer(ModMetadataExt metadata, Path jar, ClassLoader classes) throws IOException {
        this.metadata = metadata;
        this.jar = jar;
        this.root = FileSystems.newFileSystem(jar).getPath("/");
        this.classes = classes;
    }

    void close() throws IOException {
        root.getFileSystem().close();
    }

    @Override
    public ModMetadataExt metadata() {
        return metadata;
    }

    @Override
    public String pluginId() {
        return "quilt_loader";
    }

    @Override
    public String modType() {
        return "Fabric";
    }

    @Override
    public boolean shouldAddToQuiltClasspath() {
        return false;
    }

    @Override
    public Path rootPath() {
        return root;
    }

    @Override
    public List<List<Path>> getSourcePaths() {
        return List.of(List.of(jar));
    }

    @Override
    public BasicSourceType getSourceType() {
        return BasicSourceType.NORMAL_FABRIC;
    }

    @Override
    public ClassLoader getClassLoader() {
        return classes;
    }
}
