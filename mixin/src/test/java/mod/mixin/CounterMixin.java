package mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import game.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** @agent claude-fable-5-2 */
@Mixin(Counter.class)
public abstract class CounterMixin {
    @Shadow
    private int count;

    @Unique
    private int bonus = 5;

    @Shadow
    private int step() {
        throw new AssertionError();
    }

    @Inject(method = "limit", at = @At("HEAD"), cancellable = true)
    private void limit(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(count + step() + bonus);
    }

    @Redirect(method = "doubled", at = @At(value = "INVOKE", target = "Lgame/Counter;twice(I)I"))
    private int twentyfold(Counter counter, int value) {
        return value * 20;
    }

    @ModifyArgs(method = "describe", at = @At(value = "INVOKE", target = "Lgame/Counter;format(Ljava/lang/String;I)Ljava/lang/String;"))
    private void label(Args args) {
        args.set(0, "mixed");
    }

    @WrapOperation(method = "tripled", at = @At(value = "INVOKE", target = "Lgame/Counter;thrice(I)I"))
    private int thriceAndMore(Counter counter, int value, Operation<Integer> original) {
        return original.call(counter, value) + 1000;
    }
}
