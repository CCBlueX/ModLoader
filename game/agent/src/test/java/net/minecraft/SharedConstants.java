package net.minecraft;

/**
 * Where the loader asks the game for its version.
 *
 * @agent claude-fable-5-2
 */
public final class SharedConstants {

    private SharedConstants() {
    }

    public static WorldVersion getCurrentVersion() {
        return new WorldVersion("26.3");
    }
}
