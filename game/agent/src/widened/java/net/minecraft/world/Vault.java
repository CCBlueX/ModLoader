package net.minecraft.world;

/**
 * The game's Vault as a mod with an access widener compiles against it: the same members, public. The game
 * the tests run has the one from the test sources.
 *
 * @agent claude-fable-5-2
 */
public class Vault {

    public static int opened;

    public int secret;

    public int open() {
        throw new AssertionError();
    }
}
