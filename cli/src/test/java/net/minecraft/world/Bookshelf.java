package net.minecraft.world;

import java.util.function.UnaryOperator;
import org.spongepowered.asm.synthetic.args.Args$1;

/**
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public class Bookshelf extends Shelf {

    private static final String OWNER = String.valueOf("nobody");

    public String put(String book) {
        UnaryOperator<String> labelled = this::label;
        return labelled.apply(Args$1.of(book)) + " of " + OWNER;
    }
}
