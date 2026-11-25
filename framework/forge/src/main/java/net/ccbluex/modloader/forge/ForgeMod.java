package net.ccbluex.modloader.forge;

import com.electronwill.nightconfig.core.Config;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModClassLoader;
import net.ccbluex.modloader.TomlModFile;

/** @agent claude-fable-5-2 */
final class ForgeMod extends TomlModFile {

    private final List<String> mixinConfigs;

    ForgeMod(Framework framework, Path path, Config toml, List<String> nestedJars, List<String> mixinConfigs) {
        super(framework, path, toml, nestedJars);
        this.mixinConfigs = mixinConfigs;
    }

    @Override
    protected boolean isRequired(Config dependency) {
        return dependency.getOrElse("mandatory", false);
    }

    /** Forge names mixin configs in the jar manifest, not in mods.toml. */
    @Override
    public List<String> mixinConfigs() {
        return mixinConfigs;
    }

    @Override
    public void start(ModClassLoader loader) throws ReflectiveOperationException {
        try {
            RuntimeModContainer.start(path, id, loader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void stop() {
        RuntimeModContainer.remove(id);
    }
}
