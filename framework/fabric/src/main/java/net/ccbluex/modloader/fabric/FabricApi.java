package net.ccbluex.modloader.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fabric API: a jar of modules, each a mod of its own. A game Fabric did not start gets only the modules a mod uses,
 * with what they depend on. A module there misses part of its start, and the half of it that does apply to the
 * game's classes breaks what nobody asked for, such as model loading.
 *
 * @agent claude-opus-5-5
 * @agent claude-fable-5-2
 */
final class FabricApi {

    /** A class or package of Fabric API, as code names it and as a string for {@code Class.forName} does. */
    private static final Pattern NAME = Pattern.compile("net([/.])fabricmc\\1fabric\\1[\\w$/.]+");

    /** By id, the jar of every module. */
    private final Map<String, Path> modules = new HashMap<>();
    /** By internal package name, the id of the module whose jar has it. */
    private final Map<String, String> packages = new HashMap<>();
    /** By id, the modules a module depends on. */
    private final Map<String, Set<String>> depends = new HashMap<>();

    /** Reads the modules of the jar, unpacked once into {@code directory}. */
    FabricApi(Path jar, Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            unpack(jar, directory);
        }
        try (Stream<Path> jars = Files.list(directory)) {
            for (Path module : jars.toList()) {
                try (JarFile file = new JarFile(module.toFile())) {
                    JsonObject metadata;
                    try (InputStreamReader reader = new InputStreamReader(
                            file.getInputStream(file.getEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
                        metadata = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                    String id = metadata.get("id").getAsString();
                    modules.put(id, module);
                    depends.put(id, metadata.has("depends") ? metadata.getAsJsonObject("depends").keySet() : Set.of());
                    file.stream()
                            .map(JarEntry::getName)
                            .filter(name -> name.endsWith(".class") && name.contains("/"))
                            .forEach(name -> packages.put(name.substring(0, name.lastIndexOf('/')), id));
                }
            }
        }
    }

    private static void unpack(Path jar, Path directory) throws IOException {
        Files.createDirectories(directory.getParent());
        // Filled apart and moved in whole: the directory stands for a complete unpack.
        Path part = Files.createTempDirectory(directory.getParent(), directory.getFileName().toString());
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : (Iterable<JarEntry>) file.stream()::iterator) {
                if (entry.getName().startsWith("META-INF/jars/") && entry.getName().endsWith(".jar")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        Files.copy(in, part.resolve(Path.of(entry.getName()).getFileName().toString()));
                    }
                }
            }
        }
        Files.move(part, directory, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The modules {@code named} names and the ones whose classes the jar or a jar inside it names, as a type or in
     * a string such as one for {@code Class.forName}; then what they depend on, each before the modules that need it.
     */
    List<Path> used(Path jar, Set<String> named) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        named.stream().filter(modules::containsKey).forEach(ids::add);
        try (InputStream in = Files.newInputStream(jar)) {
            scan(in, ids);
        }
        Set<String> ordered = new LinkedHashSet<>();
        ids.forEach(id -> add(id, ordered));
        List<Path> jars = new ArrayList<>();
        ordered.forEach(id -> jars.add(modules.get(id)));
        return jars;
    }

    private void add(String id, Set<String> ordered) {
        if (!ordered.contains(id)) {
            depends.getOrDefault(id, Set.of()).stream()
                    .filter(modules::containsKey)
                    .forEach(dependency -> add(dependency, ordered));
            ordered.add(id);
        }
    }

    private void scan(InputStream jar, Set<String> ids) throws IOException {
        ZipInputStream zip = new ZipInputStream(jar);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (entry.getName().endsWith(".jar")) {
                scan(new ByteArrayInputStream(zip.readAllBytes()), ids);
            } else if (entry.getName().endsWith(".class")) {
                names(zip.readAllBytes(), ids);
            }
        }
    }

    /** The modules of the names in the class file, which keeps them as plain ASCII in its constant pool. */
    private void names(byte[] bytes, Set<String> ids) {
        Matcher found = NAME.matcher(new String(bytes, StandardCharsets.ISO_8859_1));
        while (found.find()) {
            String name = found.group().replace('.', '/');
            while (name.contains("/")) {
                String id = packages.get(name);
                if (id != null) {
                    ids.add(id);
                    break;
                }
                name = name.substring(0, name.lastIndexOf('/'));
            }
        }
    }
}
