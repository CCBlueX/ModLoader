package mixed.mixin;

import net.minecraft.world.Vault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Changes code that ran before the mod loaded, which the loader can only leave out and say so.
 *
 * @agent claude-fable-5-2
 */
@Mixin(Vault.class)
abstract class VaultMixin {

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Ljava/lang/Integer;parseInt(Ljava/lang/String;)I"))
    private static int openedBefore(String number) {
        return 1000;
    }
}
