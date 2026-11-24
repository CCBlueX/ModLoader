package net.ccbluex.modloader.fabric;

import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

/**
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class Fabric implements Framework {

    @Override
    public String name() {
        return "Fabric";
    }

    @Override
    public boolean isPresent() {
        // Quilt Loader ships Fabric Loader's classes, but its own class loader and mod list.
        return Framework.hasClass("net.fabricmc.loader.impl.launch.knot.Knot") && !Framework.hasClass("org.quiltmc.loader.api.QuiltLoader");
    }

    @Override
    public boolean hasStartedGame() {
        return isPresent() && !isBroughtIn();
    }

    @Override
    public boolean isLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    /**
     * Knot's bytes with access wideners and its entrypoint patches applied, as its Mixin got them. Fabric brought
     * in runs no Mixin over the game's classes.
     */
    @Override
    public byte[] preMixinClass(String name) {
        if (isBroughtIn()) {
            return null;
        }
        try {
            return FabricLauncherBase.getLauncher().getClassByteArray(name, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Without Knot the libraries Fabric's mods use are the game's own, which have no parent to share. */
    @Override
    public void share(UnaryOperator<ClassLoader> parent) {
        if (!isBroughtIn()) {
            Knot.share(parent);
        }
    }

    /**
     * For mods that depend on Fabric Loader, its API or its Kotlin: puts Fabric Loader on the game's class path,
     * starts it, and returns Fabric Language Kotlin when a mod depends on it and the Fabric API modules the mods use.
     */
    @Override
    public List<Path> bringIn(List<ModFile> mods, Instrumentation instrumentation) throws IOException {
        Set<String> needed = new HashSet<>();
        mods.forEach(mod -> needed.addAll(mod.dependencies()));
        // Quilt runs Fabric mods itself.
        if (Framework.hasClass("org.quiltmc.loader.api.QuiltLoader")
                || needed.stream().noneMatch(id -> id.equals("fabricloader") || id.equals("fabric") || id.startsWith("fabric-"))) {
            return null;
        }
        if (!isPresent() && Framework.hasClass("net.fabricmc.loader.api.FabricLoader")) {
            throw new IllegalStateException("the game runs its Fabric mods through a layer of its own, such as Sinytra Connector or Fabricated, beside which Fabric Loader cannot start");
        }
        String version;
        Path directory;
        try {
            ClassLoader game = Fabric.class.getClassLoader();
            Object current = Class.forName("net.minecraft.SharedConstants", false, game).getMethod("getCurrentVersion").invoke(null);
            version = (String) Class.forName("net.minecraft.WorldVersion", false, game).getMethod("id").invoke(current);
            if (Framework.hasClass("net.minecraft.client.Minecraft")) {
                Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, game);
                directory = ((File) minecraft.getField("gameDirectory").get(minecraft.getMethod("getInstance").invoke(null))).toPath();
            } else {
                // A dedicated server runs in its directory.
                directory = Path.of("").toAbsolutePath();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        // Everything is fetched before anything changes in the game.
        Path cache = directory.resolve(".modloader").resolve(version);
        Downloads downloads = new Downloads(cache, version);
        List<Path> loader = downloads.fabricLoader();
        List<Path> jars = new ArrayList<>();
        if (needed.contains("fabric-language-kotlin")) {
            jars.add(downloads.modrinth("fabric-language-kotlin"));
        }
        Path api = downloads.modrinth("fabric-api");
        // The first is the jar that was loaded; the mods bundled in it come from inside it.
        jars.addAll(new FabricApi(api, cache.resolve("fabric-api")).used(mods.getFirst().path, needed));
        // Fabric Loader comes in once; the next mods, or the same after a failed load, still need their modules.
        if (!isPresent()) {
            for (Path jar : loader) {
                JarFile file = new JarFile(jar.toFile());
                // A library the game has already, such as the Mixin of NeoForge, stays the game's.
                if (jar == loader.getFirst() || !hasClassOf(file)) {
                    instrumentation.appendToSystemClassLoaderSearch(file);
                }
            }
            if (!isPresent()) {
                throw new IllegalStateException("the game's class loader does not reach Fabric Loader on the class path");
            }
            try {
                RunningGame.start(loader.getFirst(), version, directory);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        if (needed.contains("fabric-api") && !isLoaded("fabric-api")) {
            try {
                RunningGame.standIn(api, "fabric-api", apiVersion(api), "Fabric API");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return jars;
    }

    private static String apiVersion(Path api) throws IOException {
        try (JarFile file = new JarFile(api.toFile());
                Reader reader = new InputStreamReader(file.getInputStream(file.getEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().get("version").getAsString();
        }
    }

    /** Whether the game has the first class of the jar. */
    private static boolean hasClassOf(JarFile jar) {
        return jar.stream()
                .map(JarEntry::getName)
                .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class"))
                .findFirst()
                .map(name -> Framework.hasClass(name.substring(0, name.length() - ".class".length()).replace('/', '.')))
                .orElse(true);
    }

    /** Whether ModLoader started Fabric Loader in this game, rather than Knot at the game's start. */
    static boolean isBroughtIn() {
        // By name: a newer ModLoader has its own copy of the class.
        return FabricLauncherBase.getLauncher().getClass().getName().equals(RunningGame.class.getName());
    }

    @Override
    public ModFile read(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry metadata = file.getJarEntry("fabric.mod.json");
            if (metadata == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(file.getInputStream(metadata), StandardCharsets.UTF_8)) {
                return new FabricMod(this, jar, JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
    }
}
