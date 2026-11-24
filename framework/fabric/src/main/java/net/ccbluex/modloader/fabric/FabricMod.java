package net.ccbluex.modloader.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

/** @agent claude-fable-5-2 */
final class FabricMod extends ModFile {

    private final JsonObject json;

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
        List<String> jars = new ArrayList<>();
        for (JsonElement jar : array(json, "jars")) {
            jars.add(jar.getAsJsonObject().get("file").getAsString());
        }
        return jars;
    }

    @Override
    public List<String> mixinConfigs() {
        String side = Framework.hasClass("net.minecraft.client.Minecraft") ? "client" : "server";
        List<String> configs = new ArrayList<>();
        for (JsonElement mixin : array(json, "mixins")) {
            if (mixin.isJsonPrimitive()) {
                configs.add(mixin.getAsString());
            } else {
                JsonObject config = mixin.getAsJsonObject();
                String environment = config.has("environment") ? config.get("environment").getAsString() : "*";
                if (environment.equals("*") || environment.equals(side)) {
                    configs.add(config.get("config").getAsString());
                }
            }
        }
        return configs;
    }

    @Override
    public boolean hasEntrypoints() {
        return json.has("entrypoints");
    }

    @Override
    public void start(ModClassLoader loader) throws ReflectiveOperationException {
        run(loader, "main", ModInitializer.class, ModInitializer::onInitialize);
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            run(loader, "client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
        } else {
            run(loader, "server", DedicatedServerModInitializer.class, DedicatedServerModInitializer::onInitializeServer);
        }
    }

    private <T> void run(ClassLoader loader, String key, Class<T> type, Consumer<T> call) throws ReflectiveOperationException {
        if (!json.has("entrypoints")) {
            return;
        }
        for (JsonElement entrypoint : array(json.getAsJsonObject("entrypoints"), key)) {
            String value = entrypoint.isJsonPrimitive()
                    ? entrypoint.getAsString()
                    : entrypoint.getAsJsonObject().get("value").getAsString();
            call.accept(type.cast(instance(loader, value)));
        }
    }

    /** {@code Class} or {@code Class::staticField}; a Kotlin object is its INSTANCE. */
    private static Object instance(ClassLoader loader, String value) throws ReflectiveOperationException {
        String[] reference = value.split("::");
        Class<?> type = Class.forName(reference[0], true, loader);
        if (reference.length == 2) {
            return type.getField(reference[1]).get(null);
        }
        try {
            return type.getField("INSTANCE").get(null);
        } catch (NoSuchFieldException e) {
            return type.getDeclaredConstructor().newInstance();
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
    }
}
