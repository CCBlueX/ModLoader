package net.ccbluex.modloader.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;
import net.ccbluex.modloader.Framework;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.minecraft.Slf4jLogHandler;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.log.Log;
import org.jspecify.annotations.Nullable;

/**
 * The game as Fabric Loader sees it when ModLoader brought Fabric in: what Knot and its Minecraft provider are in
 * a game Fabric started. The game runs already, so there is nothing to locate, transform or launch.
 *
 * @agent claude-opus-5-5
 */
final class RunningGame extends FabricLauncherBase implements GameProvider {

    private final ClassLoader game = Framework.class.getClassLoader().getParent();
    private final String version;
    private final Path directory;

    private RunningGame(String version, Path directory) {
        this.version = version;
        this.directory = directory;
    }

    /** Starts Fabric Loader from its jar on the game's class path, with the mods every Fabric game has. */
    static void start(Path loader, String version, Path directory) throws ReflectiveOperationException, IOException {
        Log.init(new Slf4jLogHandler());
        RunningGame running = new RunningGame(version, directory);
        FabricLoaderImpl.INSTANCE.setGameProvider(running);
        for (BuiltinMod mod : running.getBuiltinMods()) {
            ModContainers.add(mod);
        }
        ModContainers.add(loader, running.game);
    }

    /** A mod without code for one whose parts came in apart, as Fabric API's modules do. */
    static void standIn(Path jar, String id, String version, String name) throws ReflectiveOperationException {
        ModContainers.add(
                new BuiltinMod(List.of(jar), new BuiltinModMetadata.Builder(id, version).setName(name).build()));
    }

    @Override
    public String getGameId() {
        return "minecraft";
    }

    @Override
    public String getGameName() {
        return "Minecraft";
    }

    @Override
    public String getRawGameVersion() {
        return version;
    }

    @Override
    public String getNormalizedGameVersion() {
        return version;
    }

    /** Minecraft and Java, which Fabric's discovery adds on its own. */
    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        Path jar;
        try {
            jar = Path.of(game.loadClass(getEntrypoint()).getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (ClassNotFoundException | URISyntaxException | RuntimeException e) {
            // A framework may define the game's classes from somewhere that is not a file.
            jar = directory;
        }
        return List.of(
                new BuiltinMod(List.of(jar),
                        new BuiltinModMetadata.Builder(getGameId(), version).setName(getGameName()).build()),
                new BuiltinMod(List.of(Path.of(System.getProperty("java.home"))),
                        new BuiltinModMetadata.Builder("java", System.getProperty("java.specification.version"))
                                .setName(System.getProperty("java.vm.name"))
                                .build()));
    }

    @Override
    public String getEntrypoint() {
        return getEnvironmentType() == EnvType.CLIENT ? "net.minecraft.client.main.Main" : "net.minecraft.server.Main";
    }

    @Override
    public Path getLaunchDirectory() {
        return directory;
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        return Set.of();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        return true;
    }

    @Override
    public void initialize(FabricLauncher launcher) {
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        return new GameTransformer();
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
    }

    @Override
    public void launch(ClassLoader loader) {
        throw new UnsupportedOperationException("the game is running already");
    }

    @Override
    public Arguments getArguments() {
        return new Arguments();
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return new String[0];
    }

    /** Mods load through ModLoader's class loaders, never the game's. */
    @Override
    public void addToClassPath(Path path, String... allowedPrefixes) {
    }

    @Override
    public void setAllowedPrefixes(Path path, String... prefixes) {
    }

    @Override
    public void setValidParentClassPath(Collection<Path> paths) {
    }

    @Override
    public EnvType getEnvironmentType() {
        return Framework.hasClass("net.minecraft.client.Minecraft") ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public boolean isClassLoaded(String name) {
        return Framework.hasClass(name);
    }

    @Override
    public Class<?> loadIntoTarget(String name) throws ClassNotFoundException {
        return Class.forName(name, false, game);
    }

    @Override
    public @Nullable InputStream getResourceAsStream(String name) {
        return game.getResourceAsStream(name);
    }

    @Override
    public ClassLoader getTargetClassLoader() {
        return game;
    }

    /** As the class file says: Fabric changes no class of a game it did not start. */
    @Override
    public byte @Nullable [] getClassByteArray(String name, boolean runTransformers) throws IOException {
        try (InputStream in = game.getResourceAsStream(name.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Override
    public @Nullable Manifest getManifest(Path originPath) {
        return null;
    }

    @Override
    public List<Path> getClassPath() {
        return List.of();
    }
}
