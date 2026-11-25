package mod.broken;

import game.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** @agent claude-fable-5-2 */
@Mixin(Counter.class)
public abstract class MissingFieldMixin {
    @Shadow
    private int missing;
}
