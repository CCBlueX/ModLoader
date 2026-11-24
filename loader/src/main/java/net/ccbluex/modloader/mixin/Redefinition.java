package net.ccbluex.modloader.mixin;

import java.util.List;
import java.util.Map;

/**
 * What a mixed class becomes in a running game, where a loaded class keeps its fields, methods and
 * interfaces.
 *
 * @param target the class with its shape unchanged; methods the mixins changed call into the companion
 * @param companionName binary name of the companion
 * @param companion a class holding the changed and the added methods as static methods
 * @param interfaces binary names of the interfaces the mixins add
 * @param methods name and descriptor of every method the mixins added, to the companion method that stands for it
 * @param initializer companion method to run once before the class is redefined, null when there is none
 * @param fields name of every instance field the mixins add with an initializer, to the companion method that
 *               computes its value for an object that existed before
 * @param skipped what the mixins ask for that had to be left out, in words for the person loading the mod
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public record Redefinition(
        byte[] target,
        String companionName,
        byte[] companion,
        List<String> interfaces,
        Map<String, String> methods,
        String initializer,
        Map<String, String> fields,
        List<String> skipped) {
}
