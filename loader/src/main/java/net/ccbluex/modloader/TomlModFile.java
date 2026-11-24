package net.ccbluex.modloader;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A mod of the Forge family: described by a mods.toml, started through {@code @Mod} classes, bundling jars
 * with JarJar.
 *
 * @agent claude-fable-5-2
 */
public abstract class TomlModFile extends ModFile {

    protected final Map<String, List<Map<String, String>>> toml;
    private final List<String> nestedJars;

    protected TomlModFile(Framework framework, Path path, Map<String, List<Map<String, String>>> toml, List<String> nestedJars) {
        super(framework, path, toml.get("mods").getFirst().get("modId"));
        this.toml = toml;
        this.nestedJars = nestedJars;
    }

    /** The mods.toml at {@code entry} of the jar, or null when the jar has none or it describes no mod. */
    public static Map<String, List<Map<String, String>>> read(JarFile jar, String entry) throws IOException {
        JarEntry metadata = jar.getJarEntry(entry);
        if (metadata == null) {
            return null;
        }
        Map<String, List<Map<String, String>>> toml =
                Toml.parse(new String(jar.getInputStream(metadata).readAllBytes(), StandardCharsets.UTF_8));
        return toml.containsKey("mods") ? toml : null;
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
    protected abstract boolean isRequired(Map<String, String> dependency);

    @Override
    public List<String> dependencies() {
        return toml.getOrDefault("dependencies." + id, List.of()).stream()
                .filter(this::isRequired)
                .map(dependency -> dependency.get("modId"))
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
