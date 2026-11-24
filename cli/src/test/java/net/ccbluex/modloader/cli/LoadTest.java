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
 * @agent claude-opus-5-5
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
    void stopGame() throws InterruptedException {
        // A normal exit lets the agent delete what it unpacked.
        game.destroy();
        game.waitFor();
    }

    @Test
    void loadsOnceUnloadsAndLoadsAgain() throws Exception {
        String example = System.getProperty("example.jar");

        assertEquals("loaded example (Fabric)", cli("load", example));
        assertEquals("[example] loaded on Attach Listener", gameOutput.readLine());
        assertEquals("example is already loaded", cli("load", example));
        assertEquals("[example] mixin: tick 0 of a test server", send("server"));
        assertEquals("ticked", gameOutput.readLine());

        assertEquals("unloaded example", cli("unload", "example"));
        assertEquals("[example] unloaded", gameOutput.readLine());
        assertEquals("no mods loaded", cli("list"));
        assertEquals("ticked", send("server"));

        assertEquals("loaded example (Fabric)", cli("load", example));
        assertEquals("[example] loaded on Attach Listener", gameOutput.readLine());
    }

    @Test
    void mixesIntoLoadedClassAndBack() throws Exception {
        assertEquals("1", send("tick"));
        assertEquals("2", send("tick"));

        String loaded = "loaded mixed (Fabric)\n  left out: a change to the static initializer of net.minecraft.world.Vault, which has already run";
        assertEquals(loaded, cli("load", System.getProperty("mixed.jar")));
        assertEquals("mixed loaded", gameOutput.readLine());
        // The redirected private step counts double, then the injection cancels at three.
        assertEquals("4", send("tick"));
        assertEquals("-1", send("tick"));
        // Accessor, invoker, added interface, added field counted by a lambda, added static with initializer.
        // Then the private members of Vault the mod was compiled against as public: 42 read, 43 written.
        assertEquals("4 1 first:2:102 true 43 1", send("hook"));
        // A constructor injection, and a field initializer the counter that existed before never ran.
        assertEquals("created second as number 2 with history [second]", send("new"));
        assertEquals("second:0", gameOutput.readLine());
        assertEquals("labelled second as number 2", send("label"));
        assertEquals("second", gameOutput.readLine());

        // Another mod coming and going leaves the mixed state alone.
        cli("load", System.getProperty("example.jar"));
        assertEquals("[example] loaded on Attach Listener", gameOutput.readLine());
        assertEquals("2", send("tick"));
        assertEquals("2 1 second:1:103 true 44 2", send("hook"));

        assertEquals("unloaded mixed", cli("unload", "mixed"));
        assertEquals("mixed unloaded", gameOutput.readLine());
        assertEquals("3", send("tick"));
        assertEquals("no hook", send("hook"));

        assertEquals(loaded, cli("load", System.getProperty("mixed.jar")));
        assertEquals("mixed loaded", gameOutput.readLine());
        assertEquals("-1", send("tick"));
        assertEquals("3 1 second:1:101 true 45 3", send("hook"));
        assertEquals("shelved book", send("shelve"));
        assertEquals("[rare book] on a bookshelf of the mod", gameOutput.readLine());
    }

    private String send(String command) throws IOException {
        game.outputWriter().write(command + "\n");
        game.outputWriter().flush();
        return gameOutput.readLine();
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

    @Test
    void bundledModNeedsTheModItCameIn(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("Parent.java"), """
                public class Parent {
                    public static String name() {
                        return "parent";
                    }
                }
                """);
        Files.writeString(directory.resolve("Child.java"), """
                import net.ccbluex.modloader.api.LoadedMod;
                import net.ccbluex.modloader.api.RuntimeMod;

                public class Child implements RuntimeMod {
                    public void load(LoadedMod mod) {
                        System.out.println("child sees " + Parent.name());
                    }

                    public void unload() {
                        System.out.println("child unloaded");
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("api.jar"),
                "-d", directory.toString(), directory.resolve("Parent.java").toString(), directory.resolve("Child.java").toString()));
        Path child = directory.resolve("child.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(child))) {
            entry(out, "Child.class", Files.readAllBytes(directory.resolve("Child.class")));
            entry(out, "META-INF/services/net.ccbluex.modloader.api.RuntimeMod", "Child".getBytes());
            entry(out, "fabric.mod.json", "{\"id\": \"child\", \"depends\": {\"parent\": \"*\"}}".getBytes());
        }
        Path parent = directory.resolve("parent.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(parent))) {
            entry(out, "Parent.class", Files.readAllBytes(directory.resolve("Parent.class")));
            entry(out, "META-INF/jars/child.jar", Files.readAllBytes(child));
            entry(out, "fabric.mod.json", "{\"id\": \"parent\", \"jars\": [{\"file\": \"META-INF/jars/child.jar\"}]}".getBytes());
        }

        assertEquals("loaded parent (Fabric)", cli("load", parent.toString()));
        assertEquals("child sees parent", gameOutput.readLine());
        assertEquals(List.of("child", "parent"), cli("list").lines().map(line -> line.split(" ")[0]).sorted().toList());
        assertEquals("unloaded parent", cli("unload", "parent"));
        assertEquals("child unloaded", gameOutput.readLine());
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
