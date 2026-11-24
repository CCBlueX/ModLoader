package mod.merged;

import game.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** @agent claude-opus-5-5 */
@Mixin(Counter.class)
public abstract class FirstCounterMixin {
    @Overwrite
    public int limit() {
        return 1;
    }
}
