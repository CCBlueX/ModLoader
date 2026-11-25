package net.ccbluex.modloader.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Entry of the jar inside the game. Lives on the system class path, so it only starts the loader in a class
 * loader below the game's and passes commands on.
 *
 * @agent claude-fable-5-2
 */
public final class Agent {

    private static @Nullable UnaryOperator<String> loader;
    private static @Nullable FileTime loaderVersion;
    private static boolean isBridged;

    private Agent() {
    }

    /**
     * {@code request} is three parts split by line breaks: the file the answer goes to, this jar, the loader's
     * request. The answer is the loader's JSON, or plain text when the loader could not start.
     */
    public static synchronized void agentmain(String request, Instrumentation instrumentation) throws IOException {
        String[] parts = request.split("\n", 3);
        String answer;
        try {
            // This class stays the one from the first attach; the loader follows the jar of the latest request.
            Path jar = Path.of(parts[1]);
            FileTime version = Files.getLastModifiedTime(jar);
            UnaryOperator<String> current = loader;
            if (current == null || !version.equals(loaderVersion)) {
                if (current instanceof AutoCloseable old) {
                    old.close();
                }
                current = start(jar, instrumentation);
                loader = current;
                loaderVersion = version;
            }
            answer = current.apply(parts[2]);
        } catch (Exception | LinkageError e) {
            e.printStackTrace();
            answer = e.toString();
        }
        Files.writeString(Path.of(parts[0]), answer);
    }

    @SuppressWarnings("unchecked")
    private static UnaryOperator<String> start(Path jar, Instrumentation instrumentation)
            throws ReflectiveOperationException, IOException {
        Set<String> mains = Set.of("net.minecraft.client.main.Main", "net.minecraft.server.Main");
        ClassLoader game = Stream.of(instrumentation.getAllLoadedClasses())
                .filter(type -> mains.contains(type.getName()))
                .map(Class::getClassLoader)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no Minecraft in this JVM"));
        Path lib = unpack(jar);
        if (!isBridged) {
            // Once per game: the JVM keeps the first Bridge it loads, and redefined classes are linked against it.
            try (Stream<Path> boot = Files.list(lib.resolve("boot"))) {
                JarFile bridge = new JarFile(boot.findFirst().orElseThrow().toFile());
                instrumentation.appendToBootstrapClassLoaderSearch(bridge);
            }
            isBridged = true;
        }
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
        // Files registered later go first when the game exits, so a directory comes before what is in it.
        target.toFile().deleteOnExit();
        try (FileSystem files = FileSystems.newFileSystem(jar)) {
            Path lib = files.getPath("lib");
            // The walk names a directory before what is in it, and lib/ itself first.
            try (Stream<Path> entries = Files.walk(lib).skip(1)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    Path copy = target.resolve(lib.relativize(entry).toString());
                    Files.copy(entry, copy);
                    copy.toFile().deleteOnExit();
                }
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
