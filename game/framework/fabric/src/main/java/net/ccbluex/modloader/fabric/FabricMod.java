package net.ccbluex.modloader.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.ModClassLoader;
import net.ccbluex.modloader.ModFile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.jspecify.annotations.Nullable;

/**
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class FabricMod extends ModFile {

    private final JsonObject json;
    /** The jars Knot was told to take classes from, null before {@link #register} and in a game without Knot. */
    private URL @Nullable [] jars;

    FabricMod(Framework framework, Path path, JsonObject json) {
        super(framework, path, json.get("id").getAsString());
        this.json = json;
    }

    @Override
    public List<String> dependencies() {
        return json.has("depends") ? List.copyOf(json.getAsJsonObject("depends").keySet()) : List.of();
    }

    @Override
    public List<String> nestedJars() {
        List<String> files = new ArrayList<>();
        for (JsonElement jar : array(json, "jars")) {
            files.add(jar.getAsJsonObject().get("file").getAsString());
        }
        return files;
    }

    @Override
    public List<String> mixinConfigs() {
        boolean hasOwnNetworking = Framework.hasClass("net.neoforged.fml.ModList")
                || Framework.hasClass("net.minecraftforge.fml.ModList");
        if (id.equals("fabric-networking-api-v1") && hasOwnNetworking) {
            return List.of();
        }
        List<String> configs = new ArrayList<>();
        for (JsonElement mixin : array(json, "mixins")) {
            if (mixin.isJsonPrimitive()) {
                configs.add(mixin.getAsString());
            } else {
                JsonObject config = mixin.getAsJsonObject();
                String environment = config.has("environment") ? config.get("environment").getAsString() : "*";
                if (environment.equals("*") || environment.equals(side())) {
                    configs.add(config.get("config").getAsString());
                }
            }
        }
        return configs;
    }

    /** Only the keys Fabric runs itself; the mods that define other keys run those. */
    @Override
    public boolean hasEntrypoints() {
        JsonObject entrypoints = json.has("entrypoints") ? json.getAsJsonObject("entrypoints") : new JsonObject();
        return !array(entrypoints, "main").isEmpty() || !array(entrypoints, side()).isEmpty();
    }

    @Override
    public void register(ModClassLoader loader) throws ReflectiveOperationException, IOException {
        if (!Fabric.isBroughtIn()) {
            jars = loader.getURLs();
            Knot.show(jars, true);
        }
        ModContainers.add(path, loader);
    }

    @Override
    public void stop() {
        ModContainers.remove(id);
        if (jars != null) {
            Knot.show(jars, false);
        }
    }

    /**
     * Runs every entrypoint, as Fabric does at the game's start, and then throws the first failure with the others.
     */
    @Override
    public void start(ModClassLoader loader) {
        List<RuntimeException> failures = new ArrayList<>();
        run("main", ModInitializer.class, ModInitializer::onInitialize, failures);
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            run("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient, failures);
        } else {
            run("server", DedicatedServerModInitializer.class, DedicatedServerModInitializer::onInitializeServer,
                    failures);
        }
        if (!failures.isEmpty()) {
            failures.subList(1, failures.size()).forEach(failures.getFirst()::addSuppressed);
            throw failures.getFirst();
        }
    }

    /** Through Fabric Loader, which keeps the instance other mods get for the same entrypoint. */
    private <T> void run(String key, Class<T> type, Consumer<T> call, List<RuntimeException> failures) {
        for (EntrypointContainer<T> entrypoint : FabricLoader.getInstance().getEntrypointContainers(key, type)) {
            if (!entrypoint.getProvider().getMetadata().getId().equals(id)) {
                continue;
            }
            try {
                call.accept(entrypoint.getEntrypoint());
            } catch (RuntimeException | LinkageError e) {
                failures.add(new IllegalStateException("the " + key + " entrypoint " + entrypoint.getDefinition()
                        + " of " + id + ", which fails: " + e, e));
            }
        }
    }

    private static String side() {
        return Framework.hasClass("net.minecraft.client.Minecraft") ? "client" : "server";
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
    }
}
