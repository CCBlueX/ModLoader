package mod.misfit;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import game.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** @agent claude-opus-5-5 */
@Mixin(Counter.class)
public abstract class MisfitCounterMixin {
    @Inject(method = "limit", at = @At("HEAD"), cancellable = true)
    private void limit(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(7);
    }

    /** Written for a tick(String) the game does not have. */
    @ModifyReturnValue(method = "tick", at = @At("RETURN"))
    private int tick(int count, String reason) {
        return count;
    }
}
