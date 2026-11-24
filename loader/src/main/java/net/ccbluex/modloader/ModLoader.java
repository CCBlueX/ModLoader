package net.ccbluex.modloader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;
import net.ccbluex.modloader.mixin.MixinManager;

/**
 * The loader inside the game. The agent hands it one command per attach and returns its answer to the CLI.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class ModLoader implements UnaryOperator<String> {

    private final List<Framework> frameworks = ServiceLoader.load(Framework.class, ModLoader.class.getClassLoader())
            .stream().map(ServiceLoader.Provider::get).sorted(Comparator.comparing(Framework::name)).toList();
    private final Map<String, Mod> mods = new ConcurrentHashMap<>();
    private final Instrumentation instrumentation;
    private final MixinManager mixins;

    public ModLoader(Instrumentation instrumentation, Path lib) throws IOException, ReflectiveOperationException {
        this.instrumentation = instrumentation;
        this.mixins = new MixinManager(instrumentation, lib.resolve("mixin"), this::findModClass, this::preMixinClass);
        // Which native libraries a class loader holds, which the JDK keeps to itself.
        Module own = ModLoader.class.getModule();
        instrumentation.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
                Map.of("java.lang", Set.of(own), "jdk.internal.loader", Set.of(own)), Set.of(), Map.of());
        for (Framework framework : frameworks) {
            if (framework.isPresent()) {
                framework.share(parent -> new SharedClassLoader(parent, mods.values()));
            }
        }
    }

    /** First line is the command, every further line one jar or mod id. */
    @Override
    public String apply(String request) {
        List<String> lines = List.of(request.split("\n"));
        List<String> items = lines.subList(1, lines.size());
        return switch (lines.getFirst()) {
            case "load" -> each(items, jar -> load(Path.of(jar)));
            case "unload" -> each(items, this::unload);
            case "list" -> mods.isEmpty() ? "no mods loaded" : String.join("\n", mods.values().stream().map(Mod::toString).toList());
            // Sent by the agent before a newer modloader.jar takes over. Bundled mods go with the mod they came in.
            case "stop" -> each(mods.values().stream()
                    .filter(mod -> mods.values().stream().noneMatch(other -> other.bundled.contains(mod)))
                    .map(Mod::id)
                    .toList(), this::unload);
            default -> "error: unknown command " + lines.getFirst();
        };
    }

    private interface Command {
        String run(String item) throws Exception;
    }

    private static String each(List<String> items, Command command) {
        List<String> answers = new ArrayList<>();
        for (String item : items) {
            try {
                answers.add(command.run(item));
            } catch (Exception | LinkageError e) {
                // The CLI gets one line; the game log gets the trace a mod author needs.
                e.printStackTrace();
                answers.add("error: " + item + ": " + (e.getMessage() == null ? e : e.getMessage()));
            }
        }
        return String.join("\n", answers);
    }

    private String load(Path jar) throws Exception {
        ModFile file = read(jar);
        if (file == null) {
            throw new IllegalArgumentException("not a mod of " + frameworks.stream().map(Framework::name).toList());
        }
        if (isLoaded(file.id)) {
            return file.id + " is already loaded";
        }
        // A jar and the mods bundled in it load together, as at startup: they may depend on each other.
        List<Mod> loaded = new ArrayList<>();
        StringBuilder answer = new StringBuilder("loaded " + file.id + " (" + file.framework.name() + ")");
        try {
            open(file, loaded);
            if (!file.framework.hasStartedGame()) {
                bringIn(file.framework, loaded, answer);
            }
            for (Mod mod : loaded) {
                for (String dependency : mod.file.dependencies()) {
                    if (!isLoaded(dependency) && loaded.stream().noneMatch(other -> other.id().equals(dependency))) {
                        throw new IllegalStateException(mod.id() + " needs " + dependency + ", which is not loaded");
                    }
                }
            }
            loaded.forEach(mod -> mods.put(mod.id(), mod));
            for (Mod mod : loaded) {
                if (mod.file.framework.isPresent()) {
                    mod.file.register(mod.classLoader);
                }
            }
            MixinManager.Compilation compilation = compileMixins(mods.values());
            runOnGameThread(() -> {
                // As at the game's start, nothing of the mods runs before they have started.
                applyMixins(compilation).forEach(skipped -> answer.append("\n  left out: ").append(skipped));
                Thread thread = Thread.currentThread();
                ClassLoader context = thread.getContextClassLoader();
                try {
                    for (Mod mod : loaded) {
                        // Libraries look their services up through the thread's class loader, which is the game's.
                        thread.setContextClassLoader(mod.classLoader);
                        mod.start().forEach(skipped -> answer.append("\n  left out: ").append(skipped));
                    }
                } catch (ReflectiveOperationException e) {
                    throw new CompletionException(e);
                } finally {
                    thread.setContextClassLoader(context);
                }
            });
        } catch (Exception | LinkageError e) {
            if (loaded.stream().anyMatch(mod -> mods.get(mod.id()) == mod)) {
                // The game may still run code of the mods until their mixins are gone, so their classes stay found.
                MixinManager.Compilation reverted = compileMixins(mods.values().stream().filter(mod -> !loaded.contains(mod)).toList());
                runOnGameThread(() -> {
                    applyMixins(reverted);
                    loaded.forEach(mod -> mods.remove(mod.id(), mod));
                });
            }
            loaded.forEach(Mod::close);
            throw e;
        }
        return answer.toString();
    }

    /**
     * Adds the mod to {@code loaded}, then the mods bundled in it that the game does not have. A bundled plain
     * library joins the mod's class path.
     */
    private Mod open(ModFile file, List<Mod> loaded) throws IOException {
        Mod mod = new Mod(this, file, new ModClassLoader(file.id, file.path, mods.values(), mixins));
        loaded.add(mod);
        for (String entry : file.nestedJars()) {
            Path nested = extract(file.path, entry);
            ModFile nestedFile = read(nested);
            if (nestedFile == null) {
                mod.classLoader.add(nested.toUri().toURL());
            } else if (!isLoaded(nestedFile.id) && loaded.stream().noneMatch(other -> other.id().equals(nestedFile.id))) {
                mod.bundled.add(open(nestedFile, loaded));
            }
        }
        return mod;
    }

    /** Puts the mods that come with the framework at the front of {@code loaded}, when it comes in for them. */
    private void bringIn(Framework framework, List<Mod> loaded, StringBuilder answer) throws IOException {
        boolean wasPresent = framework.isPresent();
        List<Path> jars = framework.bringIn(loaded.stream().map(mod -> mod.file).toList(), instrumentation);
        if (jars == null) {
            return;
        }
        List<Mod> first = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Path jar : jars) {
            ModFile file = read(jar);
            if (file != null && !isLoaded(file.id)) {
                ids.add(open(file, first).id());
            }
        }
        loaded.addAll(0, first);
        if (!wasPresent || !ids.isEmpty()) {
            answer.append("\n  brought in ").append(framework.name()).append(ids.isEmpty() ? "" : " with " + String.join(", ", ids));
        }
    }

    private String unload(String id) {
        Mod mod = mods.get(id);
        if (mod == null) {
            throw new IllegalArgumentException("not loaded by ModLoader");
        }
        checkUnload(mod);
        runOnGameThread(mod::unload);
        Framework framework = mod.file.framework;
        return "unloaded " + id + (framework.isPresent() && !framework.hasStartedGame()
                ? "\n  " + framework.name() + " and the mods that came with it stay until the game exits" : "");
    }

    /** Takes the mod and the mods bundled in it out of the list. */
    void remove(Mod mod) {
        if (mods.remove(mod.id(), mod)) {
            mod.bundled.forEach(this::remove);
        }
    }

    /** Throws when the mod cannot go: before anything of it runs its unload, which could not be undone. */
    void checkUnload(Mod mod) {
        if (!mod.canUnload()) {
            throw new IllegalStateException("what its " + mod.file.framework.name()
                    + " entrypoints registered cannot be undone; restart the game");
        }
        for (Mod each : mod.withBundled()) {
            for (String library : nativeLibraries(each.classLoader)) {
                // The JVM binds a native library to its class loader until the game exits, and loads it into no other.
                throw new IllegalStateException(each.id() + " loaded native code (" + Path.of(library).getFileName()
                        + "), which stays until the game exits; restart the game to load it again");
            }
        }
    }

    /** The paths of the native libraries loaded through this class loader. */
    @SuppressWarnings("unchecked")
    private static Collection<String> nativeLibraries(ClassLoader loader) {
        try {
            // Reflection hides every field of ClassLoader; a lookup does not.
            Class<?> type = Class.forName("jdk.internal.loader.NativeLibraries");
            Object libraries = MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup())
                    .findGetter(ClassLoader.class, "libraries", type).invoke(loader);
            Object loaded = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findGetter(type, "libraries", Map.class).invoke(libraries);
            return List.copyOf(((Map<String, ?>) loaded).keySet());
        } catch (Throwable e) {
            throw new IllegalStateException("cannot read which native libraries a mod loaded", e);
        }
    }

    /**
     * Brings the game's classes in line with the mixins of the mods loaded right now.
     *
     * @return what the mixins ask for that had to be left out and was not left out before
     */
    List<String> applyMixins() {
        return applyMixins(compileMixins(mods.values()));
    }

    private MixinManager.Compilation compileMixins(Collection<Mod> mods) {
        Map<String, List<String>> configs = new HashMap<>();
        Set<String> relaxed = new HashSet<>();
        List<URL> jars = new ArrayList<>();
        for (Mod mod : mods) {
            if (!mod.file.mixinConfigs().isEmpty()) {
                configs.put(mod.id(), mod.file.mixinConfigs());
            }
            if (!mod.file.framework.hasStartedGame()) {
                relaxed.add(mod.id());
            }
            jars.addAll(List.of(mod.classLoader.getURLs()));
        }
        List<Framework.Replacement> replacements = frameworks.stream().filter(Framework::hasStartedGame)
                .flatMap(framework -> framework.replacements().stream()).toList();
        return mixins.compile(configs, relaxed, replacements, jars);
    }

    private List<String> applyMixins(MixinManager.Compilation compilation) {
        try {
            return mixins.apply(compilation);
        } catch (ReflectiveOperationException | UnmodifiableClassException e) {
            throw new IllegalStateException(e);
        }
    }

    private Class<?> findModClass(String name) {
        for (Mod mod : mods.values()) {
            Class<?> found = mod.classLoader.findOwnClass(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private byte[] preMixinClass(String name) {
        for (Framework framework : frameworks) {
            byte[] bytes = framework.isPresent() ? framework.preMixinClass(name) : null;
            if (bytes != null) {
                return bytes;
            }
        }
        return null;
    }

    /** The jar as the framework the game runs describes it, else as any framework does. */
    private ModFile read(Path jar) throws IOException {
        ModFile foreign = null;
        for (Framework framework : frameworks) {
            ModFile file = framework.read(jar);
            if (file != null && framework.isPresent()) {
                return file;
            }
            if (foreign == null) {
                foreign = file;
            }
        }
        return foreign;
    }

    private boolean isLoaded(String id) {
        return id.equals("minecraft") || id.equals("java") || mods.containsKey(id)
                || frameworks.stream().anyMatch(framework -> framework.isPresent() && framework.isLoaded(id));
    }

    private static Path extract(Path jar, String entry) throws IOException {
        Path file = Files.createTempFile("modloader-", ".jar");
        file.toFile().deleteOnExit();
        try (JarFile source = new JarFile(jar.toFile()); InputStream in = source.getInputStream(source.getJarEntry(entry))) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    /**
     * The client thread when there is a client, else the calling thread: a dedicated server has no static handle.
     * A singleplayer world's server waits meanwhile, so it runs no code of a mod halfway in.
     */
    private static void runOnGameThread(Runnable task) {
        Object minecraft = null;
        Object server = null;
        if (Framework.hasClass("net.minecraft.client.Minecraft")) {
            try {
                minecraft = Class.forName("net.minecraft.client.Minecraft", false, ModLoader.class.getClassLoader())
                        .getMethod("getInstance").invoke(null);
                server = minecraft == null ? null : minecraft.getClass().getMethod("getSingleplayerServer").invoke(minecraft);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        if (minecraft == null) {
            task.run();
            return;
        }
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        try {
            if (server instanceof Executor executor) {
                executor.execute(() -> {
                    parked.countDown();
                    try {
                        done.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                // A server that takes no more tasks has stopped ticking as well.
                parked.await(10, TimeUnit.SECONDS);
            }
            CompletableFuture.runAsync(task, (Executor) minecraft).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            done.countDown();
        }
    }
}
