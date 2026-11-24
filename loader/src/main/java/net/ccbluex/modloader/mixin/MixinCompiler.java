package net.ccbluex.modloader.mixin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.ccbluex.modloader.Framework;

/**
 * Turns the mixins of the loaded mods into changes a running game accepts. Implemented by the mixin module,
 * which runs with Mixin and ASM in a class loader of its own; one instance per set of loaded mods.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public interface MixinCompiler {

    /** @agent claude-opus-5-5 */
    record GameConfig(String name, String mod, Integer compatibility, int order) {
    }

    /**
     * @param configs mixin config names by the id of the mod they belong to
     * @param relaxed ids of the mods running outside the framework that started the game: an injector of theirs
     *                that finds no target is left out, where it would fail the compilation of any other
     * @param replacements what the game's frameworks replaced, which the injectors of relaxed mods name
     * @param gameConfigs for a class, the mixin configs the game's own Mixin applied to it; they apply again
     *                    together with {@code configs}, as they would have at startup
     * @param sources a game class by binary name as the game's own Mixin got it, the input of every compilation;
     *                null when the game's framework does not hand it over
     * @param classes a game class by binary name, as the game defined it; null when the game has none
     * @return by binary name, the redefinition of every class the configs change
     */
    Map<String, Redefinition> compile(Map<String, List<String>> configs, Set<String> relaxed, List<Framework.Replacement> replacements,
            Function<String, List<GameConfig>> gameConfigs, Function<String, byte[]> sources, Function<String, byte[]> classes,
            Predicate<String> initialized);

    /**
     * A class of a mod, with every access a loaded game class refuses handed to {@link Linker}. The same
     * array when it has none.
     */
    byte[] rewrite(ClassLoader loader, byte[] modClass);

    /** A class Mixin generates on demand, null when it has none by that name. */
    byte[] generate(String name);
}
