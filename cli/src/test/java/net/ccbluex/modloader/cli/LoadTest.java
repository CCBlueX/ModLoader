package net.ccbluex.modloader.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the built modloader.jar against a JVM that looks like a game without a mod framework.
 *
 * @agent claude-fable-5-2
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class LoadTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    private Process game;
    private BufferedReader gameOutput;

    @BeforeEach
    void startGame() throws IOException {
        game = new ProcessBuilder(JAVA, "-Xmx64m", "-cp", System.getProperty("java.class.path"), "net.minecraft.server.Main")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        gameOutput = game.inputReader();
        assertEquals("started", gameOutput.readLine());
    }

    @AfterEach
    void stopGame() {
        game.destroyForcibly();
    }

    @Test
    void loadsOnceUnloadsAndLoadsAgain() throws Exception {
        String example = System.getProperty("example.jar");

        assertEquals("loaded example (Fabric)", cli("load", example));
        assertEquals("[example] loaded on Attach Listener", gameOutput.readLine());
        assertEquals("example is already loaded", cli("load", example));

        assertEquals("unloaded example", cli("unload", "example"));
        assertEquals("[example] unloaded", gameOutput.readLine());
        assertEquals("no mods loaded", cli("list"));

        assertEquals("loaded example (Fabric)", cli("load", example));
        assertEquals("[example] loaded on Attach Listener", gameOutput.readLine());
    }

    @Test
    void modUnloadsItself(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("Fixture.java");
        Files.writeString(source, """
                import net.ccbluex.modloader.api.LoadedMod;
                import net.ccbluex.modloader.api.RuntimeMod;

                public class Fixture implements RuntimeMod {
                    public void load(LoadedMod mod) {
                        mod.unload();
                    }

                    public void unload() {
                        System.out.println("fixture unloaded");
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-cp", System.getProperty("api.jar"), "-d", directory.toString(), source.toString()));
        Path jar = directory.resolve("fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            entry(out, "Fixture.class", Files.readAllBytes(directory.resolve("Fixture.class")));
            entry(out, "META-INF/services/net.ccbluex.modloader.api.RuntimeMod", "Fixture".getBytes());
            entry(out, "fabric.mod.json", "{\"id\": \"fixture\"}".getBytes());
        }

        cli("load", jar.toString());
        assertEquals("fixture unloaded", gameOutput.readLine());
        assertEquals("no mods loaded", cli("list"));
    }

    private String cli(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(JAVA, "-Xmx64m", "-jar", System.getProperty("modloader.jar")));
        command.addAll(List.of(arguments));
        command.addAll(List.of("--pid", String.valueOf(game.pid())));
        Process cli = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(cli.getInputStream().readAllBytes()).strip();
        cli.waitFor();
        return output;
    }

    private static void entry(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }
}
