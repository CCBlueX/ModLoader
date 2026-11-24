package net.ccbluex.modloader.example;

import net.fabricmc.api.ModInitializer;

/** @agent claude-fable-5-2 */
public final class FabricEntrypoint implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[example] Fabric entrypoint ran");
    }
}
