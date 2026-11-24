package net.ccbluex.modloader.forge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModClassLoader;
import net.ccbluex.modloader.TomlModFile;

/** @agent claude-fable-5-2 */
final class ForgeMod extends TomlModFile {

    private final List<String> mixinConfigs;

    ForgeMod(Framework framework, Path path, Map<String, List<Map<String, String>>> toml, List<String> nestedJars,
            List<String> mixinConfigs) {
        super(framework, path, toml, nestedJars);
        this.mixinConfigs = mixinConfigs;
    }

    @Override
    protected boolean isRequired(Map<String, String> dependency) {
        return dependency.getOrDefault("mandatory", "false").equals("true");
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
