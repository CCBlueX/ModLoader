package mixed.mixin;

import net.minecraft.world.Bookshelf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** @agent claude-fable-5-2 */
@Mixin(Bookshelf.class)
abstract class BookshelfMixin extends ShelfMixin {

    @Redirect(method = "<clinit>",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;valueOf(Ljava/lang/Object;)Ljava/lang/String;"))
    private static String own(Object owner) {
        return "the mod";
    }

    @Inject(method = "put", at = @At("HEAD"))
    private void print(String book, CallbackInfoReturnable<String> info) {
        System.out.println("shelved " + book);
    }

    @ModifyArgs(method = "put", at = @At(value = "INVOKE",
            target = "Ljava/util/function/UnaryOperator;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private void makeRare(Args args) {
        args.set(0, "rare " + args.get(0));
    }

    @Override
    public String modName() {
        return "book" + super.modName();
    }
}
