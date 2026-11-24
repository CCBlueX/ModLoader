package net.ccbluex.modloader.example.mixin;

import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies to a server that is already ticking. Named by string so the example builds without Minecraft.
 *
 * @agent claude-fable-5-2
 */
@Mixin(targets = "net.minecraft.server.MinecraftServer")
abstract class MinecraftServerMixin {

    @Shadow
    private int tickCount;

    @Unique
    private int exampleTicks;

    @Shadow
    public abstract String getServerModName();

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void reportEveryHundredTicks(BooleanSupplier haveTime, CallbackInfo info) {
        if (exampleTicks++ % 100 == 0) {
            System.out.println("[example] mixin: tick " + tickCount + " of a " + getServerModName() + " server");
        }
    }
}
