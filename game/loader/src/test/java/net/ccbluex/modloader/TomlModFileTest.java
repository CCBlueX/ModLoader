package net.ccbluex.modloader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.electronwill.nightconfig.core.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** @agent claude-fable-5-2 */
class TomlModFileTest {

    @Test
    void readsIdAndRequiredDependencies(@TempDir Path directory) throws IOException {
        Path jar = directory.resolve("example.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            out.write("""
                    modLoader = "javafml" # the language loader

                    [[mods]]
                    modId = "example"
                    description = '''
                    Several lines,
                    [[not.a.table]] and # not a comment
                    '''

                    [[dependencies.example]]
                    modId = "neoforge"
                    type = "required"

                    [[dependencies.example]]
                    modId = 'jei'
                    type = "optional"
                    """.getBytes(StandardCharsets.UTF_8));
        }

        try (JarFile file = new JarFile(jar.toFile())) {
            TomlModFile mod = new TomlModFile(null, jar, TomlModFile.read(file, "META-INF/neoforge.mods.toml"), List.of()) {
                @Override
                protected boolean isRequired(Config dependency) {
                    return dependency.getOrElse("type", "required").equals("required");
                }

                @Override
                public void start(ModClassLoader loader) {
                }
            };
            assertEquals("example", mod.id);
            assertEquals(List.of("neoforge"), mod.dependencies());
        }
    }
}
