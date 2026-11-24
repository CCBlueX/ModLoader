package net.ccbluex.modloader.api;

/** @agent claude-fable-5-2 */
public interface LoadedMod {

    String id();

    /**
     * Calls {@link RuntimeMod#unload}, takes the mod's mixins out of the game and closes its class loader.
     * The same jar can be loaded again afterwards.
     */
    void unload();
}
