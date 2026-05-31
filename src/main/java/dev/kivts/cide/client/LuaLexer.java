package dev.kivts.cide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class LuaLexer {
    private LuaLexer() {}

    record Token(String text, int color) {}

    private static final Set<String> KEYWORDS = Set.of(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "goto", "if", "in", "local", "nil", "not", "or",
        "repeat", "return", "then", "true", "until", "while"
    );

    private static final Set<String> BUILTINS = Set.of(
        "print", "type", "pairs", "ipairs", "next", "select", "tostring",
        "tonumber", "rawget", "rawset", "rawequal", "rawlen", "require",
        "pcall", "xpcall", "error", "assert", "load", "loadfile", "dofile",
        "collectgarbage", "setmetatable", "getmetatable", "unpack",
        "string", "table", "math", "io", "os", "coroutine",
        "turtle", "peripheral", "redstone", "fs", "http", "term",
        "textutils", "colors", "colours", "vector", "gps", "keys",
        "paintutils", "window", "multishell", "pocket", "disk", "shell",
        "write", "read"
    );

    static List<Token> tokenize(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = line.length();

        while (i < len) {
            char c = line.charAt(i);

            // Line comment (--)
            if (c == '-' && i + 1 < len && line.charAt(i + 1) == '-') {
                tokens.add(new Token(line.substring(i), CideScreen.CS_COMMENT));
                break;
            }

            // String literals
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < len) {
                    char sc = line.charAt(j);
                    if (sc == c) { j++; break; }
                    if (sc == '\\') j++; // skip escaped char
                    j++;
                }
                tokens.add(new Token(line.substring(i, Math.min(j, len)), CideScreen.CS_STRING));
                i = Math.min(j, len);
                continue;
            }

            // Long string [[ ... ]] (single-line portion)
            if (c == '[' && i + 1 < len && line.charAt(i + 1) == '[') {
                int j = line.indexOf("]]", i + 2);
                if (j >= 0) {
                    tokens.add(new Token(line.substring(i, j + 2), CideScreen.CS_STRING));
                    i = j + 2;
                } else {
                    tokens.add(new Token(line.substring(i), CideScreen.CS_STRING));
                    break;
                }
                continue;
            }

            // Numbers
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(line.charAt(i + 1)))) {
                int j = i;
                if (c == '0' && j + 1 < len && (line.charAt(j + 1) == 'x' || line.charAt(j + 1) == 'X')) {
                    j += 2;
                    while (j < len && isHexDigit(line.charAt(j))) j++;
                } else {
                    while (j < len && (Character.isDigit(line.charAt(j)) || line.charAt(j) == '.')) j++;
                    if (j < len && (line.charAt(j) == 'e' || line.charAt(j) == 'E')) {
                        j++;
                        if (j < len && (line.charAt(j) == '+' || line.charAt(j) == '-')) j++;
                        while (j < len && Character.isDigit(line.charAt(j))) j++;
                    }
                }
                tokens.add(new Token(line.substring(i, j), CideScreen.CS_NUMBER));
                i = j;
                continue;
            }

            // Identifiers, keywords, builtins
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < len && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '_')) j++;
                String word = line.substring(i, j);
                int color = KEYWORDS.contains(word) ? CideScreen.CS_KEYWORD
                          : BUILTINS.contains(word)  ? CideScreen.CS_BUILTIN
                          : CideScreen.CS_NORMAL;
                tokens.add(new Token(word, color));
                i = j;
                continue;
            }

            // Operators / punctuation
            tokens.add(new Token(String.valueOf(c), CideScreen.CS_OPERATOR));
            i++;
        }

        return tokens;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
