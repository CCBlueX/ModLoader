package net.ccbluex.modloader.api;

/**
 * Entrypoint of a mod loaded into a running game. List implementations in
 * {@code META-INF/services/net.ccbluex.modloader.api.RuntimeMod}; they run in every game, whatever
 * framework it has. Both methods run on the client thread, or on the calling thread on a dedicated server.
 * <p>
 * On a client, {@link #load} runs right after the mod's mixins went in, and the game runs none of their code until
 * it returns, a singleplayer world's server included: set up there what the mixins use.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public interface RuntimeMod {

    void load(LoadedMod mod);

    /** Undo everything {@link #load} set up: listeners, threads, open screens. */
    default void unload() {
    }
}
