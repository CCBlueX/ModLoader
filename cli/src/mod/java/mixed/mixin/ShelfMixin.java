package mixed.mixin;

import mixed.Named;
import net.minecraft.world.Shelf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** @agent claude-fable-5-2 */
@Mixin(Shelf.class)
abstract class ShelfMixin implements Named {

    @Inject(method = "label", at = @At("RETURN"), cancellable = true)
    private void name(String item, CallbackInfoReturnable<String> info) {
        info.setReturnValue(info.getReturnValue() + " on a " + modName());
    }

    @Override
    public String modName() {
        return "shelf";
    }
}
