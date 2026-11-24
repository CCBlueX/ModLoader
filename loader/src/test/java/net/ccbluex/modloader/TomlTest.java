package net.ccbluex.modloader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** @agent claude-fable-5-2 */
class TomlTest {

    @Test
    void readsModsToml() {
        Map<String, List<Map<String, String>>> toml = Toml.parse("""
                modLoader = "javafml" # the language loader
                loaderVersion = "[1,)"

                [[mods]]
                modId = "example"
                description = '''
                Several lines,
                [[not.a.table]] and # not a comment
                '''
                authors = [
                    "one",
                    "two",
                ]

                [[dependencies.example]]
                modId = "neoforge"
                type = "required"

                [[dependencies.example]]
                modId = 'jei'
                mandatory = false

                [[mixins]]
                config = "example.mixins.json"
                """);

        assertEquals("javafml", toml.get("").getFirst().get("modLoader"));
        assertEquals(1, toml.get("mods").size());
        assertEquals("example", toml.get("mods").getFirst().get("modId"));
        assertEquals(List.of("neoforge", "jei"),
                toml.get("dependencies.example").stream().map(dependency -> dependency.get("modId")).toList());
        assertEquals("false", toml.get("dependencies.example").getLast().get("mandatory"));
        assertEquals("example.mixins.json", toml.get("mixins").getFirst().get("config"));
    }
}
