package net.ccbluex.modloader.neoforge;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.neoforged.fml.event.lifecycle.InterModProcessEvent;
import net.neoforged.fml.event.lifecycle.ParallelDispatchEvent;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.javafmlmod.AutomaticEventSubscriber;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.fml.loading.modscan.Scanner;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * Stands in for FMLModContainer, which only takes classes from the game's module layer. Does what FML does
 * for a mod at startup: construct the {@code @Mod} classes, subscribe the {@code @EventBusSubscriber}
 * classes and post the lifecycle events to the mod bus.
 *
 * @agent claude-fable-5-2
 */
final class RuntimeModContainer extends ModContainer {

    private final IEventBus eventBus = BusBuilder.builder().markerType(IModBusEvent.class).allowPerPhasePost().build();

    private RuntimeModContainer(IModInfo info) {
        super(info);
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }

    static void start(Path jar, String id, ClassLoader loader) throws ReflectiveOperationException, IOException {
        ModFile file = (ModFile) JarModsDotTomlModFileReader.createModFile(JarContents.ofPath(jar), ModFileDiscoveryAttributes.DEFAULT);
        ModFileScanData scan = new Scanner(file).scan();
        IModInfo info = file.getModInfos().stream().filter(mod -> mod.getModId().equals(id)).findFirst().orElseThrow();
        RuntimeModContainer container = new RuntimeModContainer(info);
        Dist dist = FMLLoader.getCurrent().getDist();
        Map<Class<?>, Object> arguments = Map.of(IEventBus.class, container.eventBus, ModContainer.class, container, Dist.class, dist);

        containers().put(id, container);
        ModLoadingContext.get().setActiveContainer(container);
        try {
            List<String> modClasses = scan.getAnnotatedBy(Mod.class, ElementType.TYPE)
                    .filter(mod -> mod.annotationData().get("value").equals(id))
                    .filter(mod -> AutomaticEventSubscriber.getSides(mod.annotationData().get("dist")).contains(dist))
                    .map(mod -> mod.clazz().getClassName())
                    .toList();
            for (String modClass : modClasses) {
                Constructor<?> constructor = Class.forName(modClass, true, loader).getConstructors()[0];
                Object[] parameters = new Object[constructor.getParameterCount()];
                for (int i = 0; i < parameters.length; i++) {
                    parameters[i] = arguments.get(constructor.getParameterTypes()[i]);
                    if (parameters[i] == null) {
                        throw new IllegalStateException(modClass + " takes a " + constructor.getParameterTypes()[i].getSimpleName()
                                + ", which a mod loaded at runtime cannot get");
                    }
                }
                constructor.newInstance(parameters);
            }
        } finally {
            ModLoadingContext.get().setActiveContainer(null);
        }
        AutomaticEventSubscriber.inject(container, scan, loader.getUnnamedModule());

        List<BiFunction<ModContainer, DeferredWorkQueue, ParallelDispatchEvent>> lifecycle = List.of(
                FMLConstructModEvent::new,
                FMLCommonSetupEvent::new,
                dist.isClient() ? FMLClientSetupEvent::new : FMLDedicatedServerSetupEvent::new,
                InterModEnqueueEvent::new,
                InterModProcessEvent::new,
                FMLLoadCompleteEvent::new);
        for (BiFunction<ModContainer, DeferredWorkQueue, ParallelDispatchEvent> event : lifecycle) {
            DeferredWorkQueue work = new DeferredWorkQueue("ModLoader");
            container.acceptEvent(event.apply(container, work));
            work.runTasks();
        }
    }

    static void remove(String id) {
        try {
            containers().remove(id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ModList's index by id, which backs isLoaded and getModContainerById and has no public way in. */
    @SuppressWarnings("unchecked")
    private static Map<String, ModContainer> containers() throws ReflectiveOperationException {
        Field index = ModList.class.getDeclaredField("indexedMods");
        index.setAccessible(true);
        return (Map<String, ModContainer>) index.get(ModList.get());
    }
}
