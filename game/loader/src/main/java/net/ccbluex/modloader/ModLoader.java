package net.ccbluex.modloader;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
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
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import net.ccbluex.modloader.mixin.MixinManager;
import org.jspecify.annotations.Nullable;

/**
 * The loader inside the game. The agent hands it one command per attach and returns its answer to the caller, both
 * as JSON; the Rust crate has the other side.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class ModLoader implements UnaryOperator<String>, AutoCloseable {

    private static final Gson GSON = new Gson();

    /**
     * {@code items} and {@code properties} are the jars and the system properties of a load, {@code item} is the mod
     * id of an unload.
     *
     * @agent claude-fable-5-2
     */
    private record Request(String command, @Nullable String item, @Nullable List<String> items,
            @Nullable Map<String, String> properties) {
    }

    /**
     * The answer to {@code game}. {@code frameworks} also names the ones ModLoader brought in.
     *
     * @agent claude-fable-5-2
     */
    private record Running(String version, String directory, List<String> frameworks, boolean isClient) {
    }

    /**
     * A mod and the jar it came from.
     *
     * @agent claude-fable-5-2
     */
    private record Entry(String id, String framework, String path) {

        Entry(ModFile file) {
            this(file.id, file.framework.name(), file.path.toString());
        }
    }

    /**
     * The answer to {@code load}: of the given jars, {@code mods} has the ones loaded and {@code present} the ones
     * whose mod the game, an earlier load or an earlier jar of this load already had.
     *
     * @agent claude-fable-5-2
     */
    private record Loaded(List<Entry> mods, List<Entry> present, List<String> notes) {
    }

    /** @agent claude-fable-5-2 */
    private record Unloaded(List<String> notes) {
    }

    /** @agent claude-fable-5-2 */
    private record LoadedMods(List<Entry> mods) {
    }

    /** @agent claude-fable-5-2 */
    private record Refusal(String error) {
    }

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

    @Override
    public String apply(String json) {
        Request request = GSON.fromJson(json, Request.class);
        Object answer;
        try {
            answer = switch (request.command()) {
                case "game" -> new Running(Game.version(), Game.directory().toString(),
                        frameworks.stream().filter(Framework::isPresent).map(Framework::name).toList(),
                        Game.isClient());
                case "load" -> load(Objects.requireNonNull(request.items(), "load needs jars"), request.properties());
                case "unload" -> unload(Objects.requireNonNull(request.item(), "unload needs a mod id"));
                case "list" -> new LoadedMods(mods.values().stream().map(mod -> new Entry(mod.file)).toList());
                default -> throw new IllegalArgumentException("unknown command " + request.command());
            };
        } catch (Exception | LinkageError e) {
            // The caller gets the message; the game log gets the trace a mod author needs.
            e.printStackTrace();
            answer = new Refusal(describe(e));
        }
        return GSON.toJson(answer);
    }

    /** Called by the agent before a newer modloader.jar takes over. Bundled mods go with the mod they came in. */
    @Override
    public void close() {
        for (Mod mod : List.copyOf(mods.values())) {
            if (mods.values().stream().noneMatch(other -> other.bundled.contains(mod))) {
                try {
                    unload(mod.id());
                } catch (RuntimeException e) {
                    System.err.println("[ModLoader] " + mod.id() + " stays loaded: " + describe(e));
                }
            }
        }
    }

    private Loaded load(List<String> items, @Nullable Map<String, String> properties) throws Exception {
        // What -D is at launch: set before anything of the mods runs.
        if (properties != null) {
            properties.forEach(System::setProperty);
        }
        List<ModFile> files = new ArrayList<>();
        List<Entry> present = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String item : items) {
            ModFile file = readMod(Path.of(item));
            if (isLoaded(file.id)) {
                present.add(new Entry(file));
            } else if (!file.framework.hasStartedGame() && file.framework.bringsIn(file.id)) {
                notes.add("left out: " + file.path.getFileName() + ", as " + file.framework.name() + " brings "
                        + file.id + " in itself");
            } else {
                files.add(file);
            }
        }
        if (files.isEmpty()) {
            return new Loaded(List.of(), present, notes);
        }
        // The jars and the mods bundled in them load together, as at startup: they may depend on each other.
        List<Mod> loaded = new ArrayList<>();
        List<Mod> given = new ArrayList<>();
        try {
            for (ModFile file : files) {
                // An earlier jar of the load may be the same mod, or bundle it.
                if (loaded.stream().anyMatch(mod -> mod.id().equals(file.id))) {
                    present.add(new Entry(file));
                } else {
                    given.add(open(file, loaded));
                }
            }
            for (Framework framework : frameworks) {
                if (!framework.hasStartedGame()
                        && loaded.stream().anyMatch(mod -> mod.file.framework == framework)) {
                    bringIn(framework, loaded, notes);
                }
            }
            checkDependencies(loaded);
            loaded.forEach(mod -> mods.put(mod.id(), mod));
            for (Mod mod : loaded) {
                if (mod.file.framework.isPresent()) {
                    mod.file.register(mod.classLoader);
                }
            }
            MixinManager.Compilation compilation = compileMixins(mods.values());
            runOnGameThread(() -> start(loaded, compilation, notes));
        } catch (Exception | LinkageError e) {
            revert(loaded);
            throw e;
        }
        List<Entry> entries = new ArrayList<>();
        for (Mod mod : given) {
            // A given jar is missing from the load when a framework brought the same mod in.
            (loaded.contains(mod) ? entries : present).add(new Entry(mod.file));
        }
        return new Loaded(entries, present, notes);
    }

    /** A jar given to {@code load}, which has to be a mod. */
    private ModFile readMod(Path jar) throws IOException {
        ModFile file;
        try {
            file = read(jar);
        } catch (IOException e) {
            throw new IOException("cannot read " + jar + ": " + e, e);
        }
        if (file == null) {
            throw new IllegalArgumentException(
                    jar + " is not a mod of " + frameworks.stream().map(Framework::name).toList());
        }
        return file;
    }

    private void checkDependencies(List<Mod> loaded) {
        for (Mod mod : loaded) {
            for (String dependency : mod.file.dependencies()) {
                if (!isLoaded(dependency) && loaded.stream().noneMatch(other -> other.id().equals(dependency))) {
                    throw new IllegalStateException(mod.id() + " needs " + dependency + ", which is not loaded");
                }
            }
        }
    }

    /**
     * On the game thread. As at the game's start, nothing of the mods runs before they have started, and every mod
     * has run its framework entrypoints before the first RuntimeMod.
     */
    private void start(List<Mod> loaded, MixinManager.Compilation compilation, List<String> notes) {
        applyMixins(compilation).forEach(skipped -> notes.add("left out: " + skipped));
        Thread thread = Thread.currentThread();
        ClassLoader context = thread.getContextClassLoader();
        try {
            for (Mod mod : loaded) {
                // Libraries look their services up through the thread's class loader, which is the game's.
                thread.setContextClassLoader(mod.classLoader);
                mod.startInFramework().forEach(skipped -> notes.add("left out: " + skipped));
            }
            for (Mod mod : loaded) {
                thread.setContextClassLoader(mod.classLoader);
                mod.start();
            }
        } catch (ReflectiveOperationException e) {
            throw new CompletionException(e);
        } finally {
            thread.setContextClassLoader(context);
        }
    }

    /** Takes the mods of a failed load out of the game again. */
    private void revert(List<Mod> loaded) {
        if (loaded.stream().anyMatch(mod -> mods.get(mod.id()) == mod)) {
            // The game may still run code of the mods until their mixins are gone, so their classes stay found.
            MixinManager.Compilation reverted =
                    compileMixins(mods.values().stream().filter(mod -> !loaded.contains(mod)).toList());
            runOnGameThread(() -> {
                applyMixins(reverted);
                loaded.forEach(mod -> mods.remove(mod.id(), mod));
            });
        }
        loaded.forEach(Mod::close);
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
            } else if (!isLoaded(nestedFile.id)
                    && loaded.stream().noneMatch(other -> other.id().equals(nestedFile.id))) {
                mod.bundled.add(open(nestedFile, loaded));
            }
        }
        return mod;
    }

    /**
     * Puts the mods that come with the framework at the front of {@code loaded}, when it comes in for its mods
     * there. A mod of the load that the framework brings as well gives way to the framework's.
     */
    private void bringIn(Framework framework, List<Mod> loaded, List<String> notes) throws IOException {
        boolean wasPresent = framework.isPresent();
        List<Path> jars = framework.bringIn(
                loaded.stream().map(mod -> mod.file).filter(file -> file.framework == framework).toList(),
                instrumentation);
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
        for (Mod mod : List.copyOf(loaded)) {
            if (first.stream().anyMatch(other -> other.id().equals(mod.id()))) {
                loaded.remove(mod);
                loaded.forEach(other -> other.bundled.remove(mod));
                mod.close();
            }
        }
        loaded.addAll(0, first);
        if (!wasPresent || !ids.isEmpty()) {
            notes.add("brought in " + framework.name() + (ids.isEmpty() ? "" : " with " + String.join(", ", ids)));
        }
    }

    private Unloaded unload(String id) {
        Mod mod = mods.get(id);
        if (mod == null) {
            throw new IllegalArgumentException("not loaded by ModLoader");
        }
        checkUnload(mod);
        runOnGameThread(mod::unload);
        Framework framework = mod.file.framework;
        return new Unloaded(framework.isPresent() && !framework.hasStartedGame()
                ? List.of(framework.name() + " and the mods that came with it stay until the game exits") : List.of());
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
            List<String> libraries = nativeLibraries(each.classLoader);
            if (!libraries.isEmpty()) {
                // The JVM binds a native library to its class loader until the game exits, and loads it into no other.
                throw new IllegalStateException(each.id() + " loaded native code ("
                        + Path.of(libraries.getFirst()).getFileName()
                        + "), which stays until the game exits; restart the game to load it again");
            }
        }
    }

    /** The paths of the native libraries loaded through this class loader. */
    @SuppressWarnings("unchecked")
    private static List<String> nativeLibraries(ClassLoader loader) {
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

    private MixinManager.Compilation compileMixins(Collection<Mod> wanted) {
        Map<String, List<String>> configs = new HashMap<>();
        Set<String> relaxed = new HashSet<>();
        List<URL> jars = new ArrayList<>();
        for (Mod mod : wanted) {
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

    private @Nullable Class<?> findModClass(String name) {
        for (Mod mod : mods.values()) {
            Class<?> found = mod.classLoader.findOwnClass(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private byte @Nullable [] preMixinClass(String name) {
        for (Framework framework : frameworks) {
            byte[] bytes = framework.isPresent() ? framework.preMixinClass(name) : null;
            if (bytes != null) {
                return bytes;
            }
        }
        return null;
    }

    /** The jar as the framework the game runs describes it, else as any framework does. */
    private @Nullable ModFile read(Path jar) throws IOException {
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
        try (FileSystem files = FileSystems.newFileSystem(jar)) {
            Files.copy(files.getPath(entry), file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    /** The message, or the exception's name when it has none. */
    static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null ? e.toString() : message;
    }

    /**
     * The client thread when there is a client, else the calling thread: a dedicated server has no static handle.
     * A singleplayer world's server waits meanwhile, so it runs no code of a mod halfway in.
     */
    private static void runOnGameThread(Runnable task) {
        Object minecraft = null;
        Object server = null;
        if (Game.isClient()) {
            try {
                ClassLoader game = ModLoader.class.getClassLoader();
                Class<?> type = Class.forName("net.minecraft.client.Minecraft", false, game);
                minecraft = type.getMethod("getInstance").invoke(null);
                server = minecraft == null ? null : type.getMethod("getSingleplayerServer").invoke(minecraft);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        if (!(minecraft instanceof Executor client)) {
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
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                });
                // A server that takes no more tasks has stopped ticking as well.
                parked.await(10, TimeUnit.SECONDS);
            }
            CompletableFuture.runAsync(task, client).join();
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
