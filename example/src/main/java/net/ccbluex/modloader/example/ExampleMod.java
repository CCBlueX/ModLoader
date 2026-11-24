package net.ccbluex.modloader.example;

import net.ccbluex.modloader.api.LoadedMod;
import net.ccbluex.modloader.api.RuntimeMod;

/**
 * Runs in every game, whatever framework it has.
 *
 * @agent claude-fable-5-2
 */
public final class ExampleMod implements RuntimeMod {

    @Override
    public void load(LoadedMod mod) {
        System.out.println("[example] loaded on " + Thread.currentThread().getName());
    }

    @Override
    public void unload() {
        System.out.println("[example] unloaded");
    }
}
