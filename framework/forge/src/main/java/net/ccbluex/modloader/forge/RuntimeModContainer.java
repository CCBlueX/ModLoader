package net.ccbluex.modloader.forge;

import cpw.mods.jarhandling.SecureJar;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import net.ccbluex.modloader.ModClassLoader;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLanguageProvider;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.fml.loading.moddiscovery.ModsFolderLocator;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.jspecify.annotations.Nullable;

/**
 * FML's own container for a mod loaded at runtime. FML finds mod classes by module, so the mod becomes a
 * module in a layer of its own below the game layer; from there FML constructs the {@code @Mod} class and
 * subscribes the {@code @EventBusSubscriber} classes itself.
 *
 * @agent claude-fable-5-2
 */
final class RuntimeModContainer extends FMLModContainer {

    private RuntimeModContainer(IModInfo info, String modClass, ModFileScanData scan, ModuleLayer layer) {
        super(info, modClass, scan, layer);
    }

    static void start(Path path, String id, ModClassLoader loader) throws ReflectiveOperationException, IOException {
        SecureJar jar = SecureJar.from(path);
        // The locator is only asked to walk the jar for the scan.
        ModFile file = new ModFile(jar, new ModsFolderLocator(), ModFileParser::modsTomlParser);
        ModFileScanData scan = file.compileContent();
        IModInfo info = file.getModInfos().stream().filter(mod -> mod.getModId().equals(id)).findFirst().orElseThrow();
        String modClass = scan.getAnnotations().stream()
                .filter(annotation -> annotation.annotationType().equals(FMLJavaModLanguageProvider.MODANNOTATION))
                .filter(annotation -> id.equals(annotation.annotationData().get("value")))
                .map(annotation -> annotation.clazz().getClassName())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no @Mod(\"" + id + "\") class"));
        ModuleLayer layer = loader.defineModule(jar.name(), FMLLoader.getGameLayer());
        RuntimeModContainer container = new RuntimeModContainer(info, modClass, scan, layer);

        index(id, container);
        // FMLModContainer's constructor puts the CONSTRUCT activity there.
        Objects.requireNonNull(container.activityMap.get(ModLoadingStage.CONSTRUCT)).run();
        List<BiFunction<ModContainer, ModLoadingStage, IModBusEvent>> lifecycle = List.of(
                FMLConstructModEvent::new,
                FMLCommonSetupEvent::new,
                FMLLoader.getDist().isClient() ? FMLClientSetupEvent::new : FMLDedicatedServerSetupEvent::new,
                FMLLoadCompleteEvent::new);
        for (BiFunction<ModContainer, ModLoadingStage, IModBusEvent> event : lifecycle) {
            container.post(event.apply(container, ModLoadingStage.COMPLETE));
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends IModBusEvent> void post(T event) {
        ((EventBus<T>) IModBusEvent.getBus(getModBusGroup(), event.getClass())).post(event);
    }

    static void remove(String id) {
        try {
            // Frees the bus group's name for the next load and drops its listeners.
            ModList.getModContainerById(id).filter(RuntimeModContainer.class::isInstance)
                    .ifPresent(container -> container.getModBusGroup().dispose());
            index(id, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ModList holds its containers in private, unmodifiable collections, so they are swapped for new ones. */
    @SuppressWarnings("unchecked")
    private static void index(String id, @Nullable ModContainer container) throws ReflectiveOperationException {
        Field byId = ModList.class.getDeclaredField("indexedMods");
        Field all = ModList.class.getDeclaredField("mods");
        byId.setAccessible(true);
        all.setAccessible(true);
        Map<String, ModContainer> indexed = new HashMap<>((Map<String, ModContainer>) byId.get(null));
        List<ModContainer> mods = new ArrayList<>((List<ModContainer>) all.get(null));
        mods.remove(indexed.remove(id));
        if (container != null) {
            indexed.put(id, container);
            mods.add(container);
        }
        byId.set(null, Map.copyOf(indexed));
        all.set(null, List.copyOf(mods));
    }
}
