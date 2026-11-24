package mod.moved;

import game.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Written for a game whose Counter has limitOf() in place of limit(), and whose doubled() calls doubleOf(int).
 *
 * @agent claude-opus-5-5
 */
@Mixin(Counter.class)
public abstract class MovedCounterMixin {
    @Inject(method = "limitOf", at = @At("HEAD"), cancellable = true)
    private void limit(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(9);
    }

    @Redirect(method = "doubled", at = @At(value = "INVOKE", target = "Lgame/Counter;doubleOf(I)I"))
    private int tenfold(Counter counter, int value) {
        return value * 10;
    }
}
