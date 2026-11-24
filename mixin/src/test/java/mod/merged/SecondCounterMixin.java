package mod.merged;

import game.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** @agent claude-opus-5-5 */
@Mixin(value = Counter.class, priority = 2000)
public abstract class SecondCounterMixin {
    @Overwrite
    public int limit() {
        return 2;
    }
}
