package mixed.mixin;

import net.minecraft.world.Vault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Changes code that ran before the mod loaded, and code the game does not have, as a game its framework did not
 * start may lack it. The loader can only leave both out and say so.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
@Mixin(Vault.class)
abstract class VaultMixin {

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Ljava/lang/Integer;parseInt(Ljava/lang/String;)I"))
    private static int openedBefore(String number) {
        return 1000;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void closedBefore(CallbackInfo info) {
    }
}
