package net.ccbluex.modloader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the part of TOML that mods.toml files use: tables, arrays of tables and string, boolean and number
 * values. A game without NeoForge or Forge has no TOML library, so their mod files are read with this.
 *
 * @agent claude-fable-5-2
 */
public final class Toml {

    private final String text;
    private int at;

    private Toml(String text) {
        this.text = text;
    }

    /** Tables by dotted name, the root table under the empty name. A plain table is a list of one. */
    public static Map<String, List<Map<String, String>>> parse(String text) {
        return new Toml(text).tables();
    }

    private Map<String, List<Map<String, String>>> tables() {
        Map<String, List<Map<String, String>>> tables = new HashMap<>();
        Map<String, String> table = new LinkedHashMap<>();
        tables.put("", List.of(table));
        while (skipBlank()) {
            if (text.charAt(at) == '[') {
                int end = text.indexOf(']', at);
                table = new LinkedHashMap<>();
                tables.computeIfAbsent(text.substring(at, end).replaceAll("[\\[\\s\"']", ""), _ -> new ArrayList<>()).add(table);
                at = text.indexOf('\n', end) < 0 ? text.length() : text.indexOf('\n', end);
            } else {
                int equals = text.indexOf('=', at);
                String key = text.substring(at, equals).strip().replaceAll("[\"']", "");
                at = equals + 1;
                table.put(key, value());
            }
        }
        return tables;
    }

    /** Moves to the next key or table header; false at the end. */
    private boolean skipBlank() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '#') {
                at = text.indexOf('\n', at) < 0 ? text.length() : text.indexOf('\n', at);
            } else if (Character.isWhitespace(c)) {
                at++;
            } else {
                return true;
            }
        }
        return false;
    }

    private String value() {
        while (text.charAt(at) == ' ' || text.charAt(at) == '\t') {
            at++;
        }
        for (String quote : List.of("\"\"\"", "'''", "\"", "'")) {
            if (text.startsWith(quote, at)) {
                int start = at + quote.length();
                int end = start;
                // A backslash only escapes in basic strings, which start with a double quote.
                while (!text.startsWith(quote, end) || (quote.charAt(0) == '"' && text.charAt(end - 1) == '\\')) {
                    end++;
                }
                at = end + quote.length();
                return text.substring(start, end);
            }
        }
        // Numbers, booleans and arrays stay as written; an array may span lines.
        int start = at;
        int depth = 0;
        while (at < text.length() && (depth > 0 || (text.charAt(at) != '\n' && text.charAt(at) != '#'))) {
            depth += text.charAt(at) == '[' ? 1 : text.charAt(at) == ']' ? -1 : 0;
            at++;
        }
        return text.substring(start, at).strip();
    }
}
