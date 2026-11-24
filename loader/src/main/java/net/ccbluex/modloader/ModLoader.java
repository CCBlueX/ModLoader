package net.ccbluex.modloader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;

/**
 * The loader inside the game. The agent hands it one command per attach and returns its answer to the CLI.
 *
 * @agent claude-fable-5-2
 */
public final class ModLoader implements UnaryOperator<String> {

    private final List<Framework> frameworks = ServiceLoader.load(Framework.class, ModLoader.class.getClassLoader())
            .stream().map(ServiceLoader.Provider::get).sorted(Comparator.comparing(Framework::name)).toList();
    private final Map<String, Mod> mods = new ConcurrentHashMap<>();

    public ModLoader(Instrumentation instrumentation, Path lib) {
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
            // Sent by the agent before a newer modloader.jar takes over.
            case "stop" -> each(List.copyOf(mods.keySet()), this::unload);
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
        Mod mod = new Mod(this, file, new ModClassLoader(file.id, jar, mods.values()));
        try {
            for (String entry : file.nestedJars()) {
                Path nested = extract(jar, entry);
                // A bundled mod loads as its own mod unless the game has it; a plain library joins this mod.
                if (read(nested) != null) {
                    load(nested);
                } else {
                    mod.classLoader.add(nested.toUri().toURL());
                }
            }
            for (String dependency : file.dependencies()) {
                if (!isLoaded(dependency)) {
                    throw new IllegalStateException("needs " + dependency + ", which is not loaded");
                }
            }
            mods.put(file.id, mod);
            runOnGameThread(() -> {
                try {
                    mod.start();
                } catch (ReflectiveOperationException e) {
                    throw new CompletionException(e);
                }
            });
        } catch (Exception | LinkageError e) {
            mods.remove(file.id, mod);
            mod.close();
            throw e;
        }
        return "loaded " + file.id + " (" + file.framework.name() + ")";
    }

    private String unload(String id) {
        Mod mod = mods.get(id);
        if (mod == null) {
            throw new IllegalArgumentException("not loaded by ModLoader");
        }
        if (!mod.canUnload()) {
            throw new IllegalStateException("what its " + mod.file.framework.name()
                    + " entrypoints registered cannot be undone; restart the game");
        }
        runOnGameThread(mod::unload);
        return "unloaded " + id;
    }

    boolean remove(Mod mod) {
        return mods.remove(mod.id(), mod);
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

    /** The client thread when there is a client, else the calling thread: a dedicated server has no static handle. */
    private static void runOnGameThread(Runnable task) {
        Object minecraft = null;
        if (Framework.hasClass("net.minecraft.client.Minecraft")) {
            try {
                minecraft = Class.forName("net.minecraft.client.Minecraft", false, ModLoader.class.getClassLoader())
                        .getMethod("getInstance").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        if (minecraft == null) {
            task.run();
            return;
        }
        try {
            CompletableFuture.runAsync(task, (Executor) minecraft).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }
}
