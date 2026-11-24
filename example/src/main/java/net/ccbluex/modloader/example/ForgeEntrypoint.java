package net.ccbluex.modloader.example;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** @agent claude-fable-5-2 */
@Mod("example")
public final class ForgeEntrypoint {

    public ForgeEntrypoint(FMLJavaModLoadingContext context) {
        System.out.println("[example] Forge entrypoint ran for " + context.getContainer().getModId());
        FMLCommonSetupEvent.getBus(context.getModBusGroup())
                .addListener(event -> System.out.println("[example] Forge common setup"));
    }
}
