package net.minecraft.world;

/**
 * Members a mod only reaches with an access widener, and a static initializer that has run by the time a
 * mod loads.
 *
 * @agent claude-fable-5-2
 */
public class Vault {

    private static int opened = Integer.parseInt("0");

    private int secret = 42;

    private int open() {
        opened++;
        return secret;
    }
}
