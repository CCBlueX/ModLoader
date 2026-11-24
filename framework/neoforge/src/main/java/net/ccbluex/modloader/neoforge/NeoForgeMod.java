package net.ccbluex.modloader.neoforge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModClassLoader;
import net.ccbluex.modloader.TomlModFile;

/** @agent claude-fable-5-2 */
final class NeoForgeMod extends TomlModFile {

    NeoForgeMod(Framework framework, Path path, Map<String, List<Map<String, String>>> toml, List<String> nestedJars) {
        super(framework, path, toml, nestedJars);
    }

    @Override
    protected boolean isRequired(Map<String, String> dependency) {
        return dependency.getOrDefault("type", "required").equalsIgnoreCase("required");
    }

    @Override
    public List<String> mixinConfigs() {
        return toml.getOrDefault("mixins", List.of()).stream().map(mixin -> mixin.get("config")).toList();
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
