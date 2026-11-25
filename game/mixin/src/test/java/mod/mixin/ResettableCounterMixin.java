package mod.mixin;

import game.Counter;
import mod.Resettable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** @agent claude-fable-5-2 */
@Mixin(Counter.class)
public abstract class ResettableCounterMixin implements Resettable {
    @Shadow
    private int count;

    @Override
    public void reset() {
        count = 0;
    }
}
