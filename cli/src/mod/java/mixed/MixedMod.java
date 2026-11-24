package mixed;

import mixed.mixin.CounterAccessor;
import net.ccbluex.modloader.api.LoadedMod;
import net.ccbluex.modloader.api.RuntimeMod;
import net.minecraft.server.Main;
import net.minecraft.world.Vault;

/**
 * The mod LoadTest loads: from a class that is no mixin, it uses what its mixins add to the game's Counter
 * and the private members of Vault that it was compiled against as public.
 *
 * @agent claude-fable-5-2
 */
public final class MixedMod implements RuntimeMod {

    @Override
    public void load(LoadedMod mod) {
        Main.hook = () -> {
            Object counter = Main.counter;
            CounterAccessor accessor = (CounterAccessor) counter;
            Main.vault.secret = Main.vault.open() + 1;
            return accessor.getCount() + " " + accessor.callStep() + " " + ((Named) counter).modName() + " " + (counter instanceof Named)
                    + " " + Main.vault.secret + " " + Vault.opened;
        };
        System.out.println("mixed loaded");
    }

    @Override
    public void unload() {
        Main.hook = null;
        System.out.println("mixed unloaded");
    }
}
