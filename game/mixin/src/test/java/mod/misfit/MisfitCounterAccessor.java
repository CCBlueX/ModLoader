package mod.misfit;

import game.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** @agent claude-opus-5-5 */
@Mixin(Counter.class)
public interface MisfitCounterAccessor {
    /** Written for a game whose Counter has no doubled(). */
    @Accessor("count")
    int doubled();
}
