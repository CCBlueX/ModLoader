package net.ccbluex.modloader.example;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/** @agent claude-fable-5-2 */
@Mod("example")
public final class NeoForgeEntrypoint {

    public NeoForgeEntrypoint(IEventBus modBus, ModContainer container) {
        System.out.println("[example] NeoForge entrypoint ran for " + container.getModId());
        modBus.addListener(FMLCommonSetupEvent.class, event -> System.out.println("[example] NeoForge common setup"));
    }
}
