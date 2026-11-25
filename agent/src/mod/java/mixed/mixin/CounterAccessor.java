package mixed.mixin;

import net.minecraft.world.Counter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** @agent claude-fable-5-2 */
@Mixin(Counter.class)
public interface CounterAccessor {

    @Accessor("count")
    int getCount();

    @Invoker("step")
    int callStep();
}
