package mixed.mixin;

import net.minecraft.world.Label;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** @agent claude-fable-5-2 */
@Mixin(Label.class)
abstract class LabelMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void print(int number, String text, CallbackInfo info) {
        System.out.println("labelled " + text + " as number " + number);
    }
}
