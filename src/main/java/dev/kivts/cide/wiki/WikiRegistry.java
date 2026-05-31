package dev.kivts.cide.wiki;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Collections;


public final class WikiRegistry {
    private WikiRegistry() {}

    private static volatile boolean loaded = false;
    private static final Map<String, List<WikiLine>> PAGES   = new LinkedHashMap<>();
    private static final Map<String, String>         SYMBOLS = new LinkedHashMap<>();
    private static final Map<String, List<String>>   MEMBERS = new LinkedHashMap<>();

    /** Returns the page key for a symbol name (e.g. "speaker" >>> "peripheral/speaker"), or null. */
    public static String resolveSymbol(String name) {
        ensureLoaded();
        return SYMBOLS.get(name);
    }

    public static List<WikiLine> getPage(String key) {
        ensureLoaded();
        return PAGES.getOrDefault(key, List.of());
    }

    public static synchronized void registerPage(String key, List<WikiLine> lines) {
        ensureLoaded();
        PAGES.put(key, lines);
    }

    public static void registerPage(String key, String markdown) {
        registerPage(key, parseMarkdown(markdown));
    }

    private static List<WikiLine> parseMarkdown(String text) {
        List<WikiLine> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if      (line.startsWith("### ")) out.add(WikiLine.sig(line.substring(4)));
            else if (line.startsWith("## "))  out.add(WikiLine.h2(line.substring(3)));
            else if (line.startsWith("# "))   out.add(WikiLine.h1(line.substring(2)));
            else if (line.equals("---"))       out.add(WikiLine.rule());
            else if (line.startsWith("- "))   out.add(WikiLine.bullet(line.substring(2)));
            else if (line.isEmpty())           out.add(new WikiLine("", 0, false, 0));
            else                               out.add(WikiLine.body(line));
        }
        // Trim leading/trailing blank lines
        while (!out.isEmpty() && out.get(0).text().isEmpty() && !out.get(0).hr())
            out.remove(0);
        while (!out.isEmpty() && out.get(out.size() - 1).text().isEmpty() && !out.get(out.size() - 1).hr())
            out.remove(out.size() - 1);
        return Collections.unmodifiableList(out);
    }

    public static synchronized void registerSymbol(String name, String pageKey) {
        ensureLoaded();
        SYMBOLS.put(name, pageKey);
    }

    public static Set<String> getSymbols() {
        ensureLoaded();
        return Collections.unmodifiableSet(SYMBOLS.keySet());
    }

    public static Map<String, List<String>> getMembers() {
        ensureLoaded();
        return Collections.unmodifiableMap(MEMBERS);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        loadPages();
        loadSymbols();
        loadExternalIntegrations();
    }

    private static void loadPages() {
        try (InputStream is = WikiRegistry.class.getClassLoader()
                .getResourceAsStream("data/cide/wiki/pages.json")) {
            if (is == null) return;
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                List<WikiLine> lines = parsePage(entry.getValue());
                PAGES.put(entry.getKey(), Collections.unmodifiableList(lines));
            }
        } catch (Exception ignored) {}
    }

    private static void loadSymbols() {
        try (InputStream is = WikiRegistry.class.getClassLoader()
                .getResourceAsStream("data/cide/wiki/symbols.json")) {
            if (is == null) return;
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                SYMBOLS.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (Exception ignored) {}
    }

    private static void loadExternalIntegrations() {
        try {
            for (IModFileInfo fileInfo : ModList.get().getModFiles()) {
                Path root = fileInfo.getFile().getSecureJar().getRootPath();
                for (IModInfo modInfo : fileInfo.getMods()) {
                    String modId = modInfo.getModId();
                    Path cideRoot = root.resolve("data").resolve(modId).resolve("cide");
                    Path wikiDir = cideRoot.resolve("wiki");
                    if (Files.isDirectory(wikiDir)) {
                        try (var files = Files.walk(wikiDir)) {
                            files
                                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                                .forEach(path -> loadExternalIntegration(path, fileNameWithoutJson(path)));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void loadExternalIntegration(Path path, String defaultSymbol) {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonArray()) {
                for (JsonElement entry : root.getAsJsonArray()) loadExternalEntry(path, entry.getAsJsonObject(), defaultSymbol);
            } else {
                JsonObject object = root.getAsJsonObject();
                JsonArray entries = object.getAsJsonArray("entries");
                if (entries != null) {
                    for (JsonElement entry : entries) loadExternalEntry(path, entry.getAsJsonObject(), defaultSymbol);
                } else {
                    loadExternalEntry(path, object, defaultSymbol);
                }
            }
        } catch (IOException | RuntimeException ignored) {}
    }

    private static void loadExternalEntry(Path source, JsonObject entry, String defaultSymbol) {
        String siblingMarkdown = "";
        if (!entry.has("wiki") && !entry.has("page")) {
            siblingMarkdown = readSiblingText(source, fileNameWithoutJson(source) + ".md");
        }
        applyEntryObject(entry, defaultSymbol, siblingMarkdown);
    }

    /**
     * Applies one wiki integration entry (the shared mod/datapack JSON format) into the
     * registry. {@code siblingMarkdown} is pre-resolved wiki text used only when the entry
     * has neither an inline {@code wiki} nor {@code page} field; pass "" if none.
     * Returns the resolved symbol when the entry produced a code page, otherwise null.
     */
    private static String applyEntryObject(JsonObject entry, String defaultSymbol, String siblingMarkdown) {
        String symbol = getString(entry, "symbol", defaultSymbol == null ? "" : defaultSymbol).trim();
        if (symbol.isEmpty()) return null;

        String pageKey = getString(entry, "pageKey", "").trim();
        if (pageKey.isEmpty()) {
            String kind = getString(entry, "kind", "module").trim();
            pageKey = kind + "/" + symbol;
        }
        if (!pageKey.startsWith("module/") && !pageKey.startsWith("peripheral/") && !pageKey.startsWith("generic_peripheral/")) return null;

        SYMBOLS.put(symbol, pageKey);
        for (String alias : readAliases(entry)) SYMBOLS.put(alias, pageKey);

        List<String> members = readStringList(entry.getAsJsonArray("members"));
        if (!members.isEmpty()) {
            List<String> frozenMembers = Collections.unmodifiableList(members);
            MEMBERS.put(symbol, frozenMembers);
            for (String alias : readAliases(entry)) MEMBERS.put(alias, frozenMembers);
        }

        if (entry.has("wiki")) {
            PAGES.put(pageKey, parseMarkdown(readWikiText(entry.get("wiki"))));
        } else if (siblingMarkdown != null && !siblingMarkdown.isEmpty()) {
            PAGES.put(pageKey, parseMarkdown(siblingMarkdown));
        }

        if (entry.has("page")) {
            PAGES.put(pageKey, Collections.unmodifiableList(parsePage(entry.get("page"))));
        }
        return symbol;
    }

    /**
     * Applies datapack-contributed entries received from the server (a JSON array in the
     * shared mod/datapack format, with any sibling .md already inlined server-side).
     * Returns symbol -> members for each entry that produced a code page, so the caller
     * can feed the autocomplete registry. Safe to call repeatedly; entries are additive.
     */
    public static synchronized Map<String, List<String>> ingestDatapackEntries(String json) {
        ensureLoaded();
        Map<String, List<String>> registered = new LinkedHashMap<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return registered;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String symbol = applyEntryObject(entry, getString(entry, "symbol", ""), "");
                if (symbol == null) continue;
                registered.put(symbol, MEMBERS.getOrDefault(symbol, List.of()));
                for (String alias : readAliases(entry)) {
                    registered.put(alias, MEMBERS.getOrDefault(alias, List.of()));
                }
            }
        } catch (RuntimeException ignored) {}
        return registered;
    }

    private static String fileNameWithoutJson(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    private static String readWikiText(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonElement line : element.getAsJsonArray()) lines.add(line.getAsString());
            return String.join("\n", lines);
        }
        return element.getAsString();
    }

    private static String readSiblingText(Path source, String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) return "";
        Path parent = source.getParent();
        Path target = parent.resolve(fileName).normalize();
        if (!target.getParent().equals(parent) || !Files.isRegularFile(target)) return "";
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static List<String> readStringList(JsonArray values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            String text = value.getAsString().trim();
            if (!text.isEmpty() && !result.contains(text)) result.add(text);
        }
        return result;
    }

    private static List<String> readAliases(JsonObject entry) {
        List<String> aliases = new ArrayList<>();
        aliases.addAll(readStringList(entry.getAsJsonArray("aliases")));
        aliases.addAll(readStringList(entry.getAsJsonArray("symbols")));
        return aliases;
    }

    private static WikiLine parseWikiLine(JsonObject o) {
        return parseWikiLine(o, WikiLine.C_BODY, 0);
    }

    private static WikiLine parseWikiLine(JsonObject o, int fallbackColor, int fallbackIndent) {
        String text   = o.has("text")   ? o.get("text").getAsString()   : "";
        int    color  = parseColor(o.get("color"), fallbackColor);
        boolean hr     = o.has("hr")     && o.get("hr").getAsBoolean();
        int     indent = o.has("indent") ? o.get("indent").getAsInt()    : fallbackIndent;
        boolean bullet = o.has("bullet") && o.get("bullet").getAsBoolean();
        return new WikiLine(text, color, hr, indent, bullet);
    }

    private static List<WikiLine> parsePage(JsonElement element) {
        if (element.isJsonArray()) {
            List<WikiLine> lines = new ArrayList<>();
            for (JsonElement el : element.getAsJsonArray()) lines.add(parseWikiLine(el.getAsJsonObject()));
            return lines;
        }

        JsonObject page = element.getAsJsonObject();
        List<WikiLine> lines = new ArrayList<>();
        lines.add(new WikiLine(
            getString(page, "title", ""),
            parseColor(page.get("titleColor"), WikiLine.C_H1),
            false,
            0
        ));
        int summaryColor = parseColor(page.get("summaryColor"), WikiLine.C_BODY);
        int entryDescriptionColor = parseColor(page.get("entryDescriptionColor"), WikiLine.C_BODY);
        addBodyLines(lines, page.getAsJsonArray("summary"), summaryColor, 0);

        JsonArray entries = page.getAsJsonArray("entries");
        if (entries != null) {
            for (JsonElement entryElement : entries) {
                JsonObject entry = entryElement.getAsJsonObject();
                lines.add(WikiLine.rule());
                lines.add(new WikiLine(
                    getString(entry, "signature", ""),
                    parseColor(entry.get("signatureColor"), WikiLine.C_SIG),
                    false,
                    1
                ));
                addBodyLines(lines, entry.getAsJsonArray("description"), parseColor(entry.get("descriptionColor"), entryDescriptionColor), 1);
            }
        }
        return lines;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static void addBodyLines(List<WikiLine> lines, JsonArray values, int color, int indent) {
        if (values == null) return;
        for (JsonElement value : values) {
            if (value.isJsonObject()) {
                lines.add(parseWikiLine(value.getAsJsonObject(), color, indent));
            } else {
                String text = value.getAsString();
                lines.add(new WikiLine(text, color, false, indent));
            }
        }
    }

    private static int parseColor(JsonElement value, int fallback) {
        if (value == null || value.isJsonNull()) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return value.getAsInt();

        String raw = value.getAsString().trim();
        if (raw.isEmpty()) return fallback;
        if (raw.startsWith("#")) raw = raw.substring(1);
        if (raw.startsWith("0x") || raw.startsWith("0X")) raw = raw.substring(2);

        try {
            return (int) Long.parseUnsignedLong(raw, 16);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
