package net.ccbluex.modloader.neoforge;

import com.electronwill.nightconfig.core.Config;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModClassLoader;
import net.ccbluex.modloader.TomlModFile;

/** @agent claude-fable-5-2 */
final class NeoForgeMod extends TomlModFile {

    NeoForgeMod(Framework framework, Path path, Config toml, List<String> nestedJars) {
        super(framework, path, toml, nestedJars);
    }

    @Override
    protected boolean isRequired(Config dependency) {
        return dependency.getOrElse("type", "required").equalsIgnoreCase("required");
    }

    @Override
    public List<String> mixinConfigs() {
        return toml.<List<Config>>getOrElse("mixins", List.of()).stream().map(mixin -> mixin.<String>get("config")).toList();
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
