package net.ccbluex.modloader.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Entry of the jar inside the game. Lives on the system class path, so it only starts the loader in a class
 * loader below the game's and passes commands on.
 *
 * @agent claude-fable-5-2
 */
public final class Agent {

    private static UnaryOperator<String> loader;
    private static FileTime loaderVersion;

    private Agent() {
    }

    /** {@code request} is three parts split by line breaks: the file the answer goes to, the CLI's jar, the command. */
    public static synchronized void agentmain(String request, Instrumentation instrumentation) throws IOException {
        String[] parts = request.split("\n", 3);
        String answer;
        try {
            // This class stays the one from the first attach; the loader follows the jar the CLI runs from.
            Path jar = Path.of(parts[1]);
            FileTime version = Files.getLastModifiedTime(jar);
            String stopped = "";
            if (!version.equals(loaderVersion)) {
                if (loader != null) {
                    stopped = loader.apply("stop");
                }
                loader = start(jar, instrumentation);
                loaderVersion = version;
            }
            answer = (stopped.isEmpty() ? "" : stopped + "\n") + loader.apply(parts[2]);
        } catch (Exception | LinkageError e) {
            e.printStackTrace();
            answer = "error: " + e;
        }
        Files.writeString(Path.of(parts[0]), answer);
    }

    @SuppressWarnings("unchecked")
    private static UnaryOperator<String> start(Path jar, Instrumentation instrumentation)
            throws ReflectiveOperationException, IOException {
        ClassLoader game = Stream.of(instrumentation.getAllLoadedClasses())
                .filter(type -> List.of("net.minecraft.client.main.Main", "net.minecraft.server.Main").contains(type.getName()))
                .map(Class::getClassLoader)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no Minecraft in this JVM"));
        Path lib = unpack(jar);
        try (Stream<Path> jars = Files.list(lib.resolve("runtime"))) {
            URL[] urls = jars.map(Agent::url).toArray(URL[]::new);
            return (UnaryOperator<String>) new URLClassLoader("modloader", urls, game)
                    .loadClass("net.ccbluex.modloader.ModLoader")
                    .getConstructor(Instrumentation.class, Path.class)
                    .newInstance(instrumentation, lib);
        }
    }

    /** Copies lib/ of the jar to a temporary directory, as class loaders cannot read jars inside a jar. */
    private static Path unpack(Path jar) throws IOException {
        Path target = Files.createTempDirectory("modloader");
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : (Iterable<JarEntry>) file.stream()::iterator) {
                if (!entry.getName().startsWith("lib/") || entry.isDirectory()) {
                    continue;
                }
                Path copy = target.resolve(entry.getName().substring("lib/".length()));
                Files.createDirectories(copy.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, copy);
                }
                copy.toFile().deleteOnExit();
            }
        }
        return target;
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
