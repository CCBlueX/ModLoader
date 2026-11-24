package net.minecraft.server;

import java.util.function.BooleanSupplier;

/**
 * The members of the game's server class that the example mod's mixin uses.
 *
 * @agent claude-fable-5-2
 */
public class MinecraftServer {

    private int tickCount;

    public String getServerModName() {
        return "test";
    }

    protected void tickServer(BooleanSupplier haveTime) {
        tickCount++;
    }
}
