package mixed.mixin;

import java.util.ArrayList;
import java.util.List;
import mixed.Named;
import net.minecraft.world.Counter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One of each thing a loaded class cannot take as it is: handler methods, fields with and without an
 * initializer, a static with one, an interface, a lambda, a constructor injection, an enum switch.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
@Mixin(Counter.class)
abstract class CounterMixin implements Named {

    @Shadow
    protected static int created;

    @Unique
    private static int total = 100;

    @Shadow
    @Final
    private String name;

    @Shadow
    private int count;

    @Unique
    private int ticks;

    @Unique
    private final List<String> history = new ArrayList<>();

    @Shadow
    abstract int step();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void stopAtThree(CallbackInfoReturnable<Integer> info) {
        Runnable counted = () -> ticks++;
        counted.run();
        total++;
        if (count >= 3) {
            info.setReturnValue(-1);
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Counter;step()I"))
    private int doubleStep(Counter counter) {
        // javac puts the table of an enum switch in a package-private class beside the mixin, which Mixin copies.
        return switch (Thread.State.RUNNABLE) {
            case RUNNABLE -> step() * 2;
            default -> step();
        };
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void remember(String name, CallbackInfo info) {
        history.add(name);
        System.out.println("created " + name + " as number " + created + " with history " + history);
    }

    @Override
    public String modName() {
        return name + ":" + ticks + ":" + total;
    }
}
