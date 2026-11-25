package net.ccbluex.modloader;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;

/**
 * A mod of the Forge family: described by a mods.toml, started through {@code @Mod} classes, bundling jars
 * with JarJar.
 *
 * @agent claude-fable-5-2
 */
public abstract class TomlModFile extends ModFile {

    protected final Config toml;
    private final List<String> nestedJars;

    protected TomlModFile(Framework framework, Path path, Config toml, List<String> nestedJars) {
        List<Config> mods = toml.get("mods");
        super(framework, path, mods.getFirst().get("modId"));
        this.toml = toml;
        this.nestedJars = nestedJars;
    }

    /** The mods.toml at {@code entry} of the jar, or null when the jar has none or it describes no mod. */
    public static @Nullable Config read(JarFile jar, String entry) throws IOException {
        JarEntry metadata = jar.getJarEntry(entry);
        if (metadata == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(metadata)) {
            Config toml = new TomlParser().parse(in);
            return toml.contains("mods") ? toml : null;
        }
    }

    public static List<String> nestedJars(JarFile jar) throws IOException {
        JarEntry metadata = jar.getJarEntry("META-INF/jarjar/metadata.json");
        List<String> jars = new ArrayList<>();
        if (metadata != null) {
            try (Reader reader = new InputStreamReader(jar.getInputStream(metadata), StandardCharsets.UTF_8)) {
                for (JsonElement nested : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("jars")) {
                    jars.add(nested.getAsJsonObject().get("path").getAsString());
                }
            }
        }
        return jars;
    }

    /** Whether a {@code [[dependencies.<id>]]} table names a mod this one cannot run without. */
    protected abstract boolean isRequired(Config dependency);

    @Override
    public List<String> dependencies() {
        return toml.<List<Config>>getOrElse(List.of("dependencies", id), List.of()).stream()
                .filter(this::isRequired)
                .map(dependency -> dependency.<String>get("modId"))
                .toList();
    }

    @Override
    public List<String> nestedJars() {
        return nestedJars;
    }

    @Override
    public boolean hasEntrypoints() {
        return true;
    }
}
