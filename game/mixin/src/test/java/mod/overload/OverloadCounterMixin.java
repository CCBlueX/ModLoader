package mod.overload;

import game.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** @agent claude-opus-5-5 */
@Mixin(Counter.class)
public abstract class OverloadCounterMixin {
    @Inject(method = "offset", at = @At("HEAD"), cancellable = true)
    private void offset(int value, CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(value + 1000);
    }

    @Redirect(method = "scale", at = @At(value = "INVOKE", target = "Ljava/lang/Math;abs(I)I"))
    private int keepSign(int value) {
        return value;
    }
}
