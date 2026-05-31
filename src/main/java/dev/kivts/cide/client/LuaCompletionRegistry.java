package dev.kivts.cide.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kivts.cide.wiki.WikiRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Lua symbol and member registry for the in-editor autocomplete.

 */
public final class LuaCompletionRegistry {
    private LuaCompletionRegistry() {}

    private static volatile boolean seeded = false;
    private static final Set<String>             SYMBOLS = new LinkedHashSet<>();
    private static final Map<String, List<String>> MEMBERS = new LinkedHashMap<>();
    private static final Set<String>             DYNAMIC_GLOBALS = new LinkedHashSet<>();
    private static final Set<String>             ITEM_NAMESPACES = new LinkedHashSet<>();

    private static final Set<String> CODE_PREFIXES =
        Set.of("module/", "peripheral/", "generic_peripheral/");

    private static final Map<String, List<String>> STD_MEMBERS = Map.of(
        "math",      List.of("abs","ceil","cos","deg","exp","floor","fmod","huge","ldexp","log",
                             "max","min","modf","pi","pow","rad","random","randomseed","sin","sqrt","tan"),
        "string",    List.of("byte","char","dump","find","format","gmatch","gsub","len",
                             "lower","match","rep","reverse","sub","upper"),
        "table",     List.of("concat","insert","maxn","move","pack","remove","sort","unpack"),
        "coroutine", List.of("create","isyieldable","resume","running","status","wrap","yield"),
        "bit32",     List.of("arshift","band","bnot","bor","btest","bxor","extract",
                             "lshift","replace","rshift","lrotate","rrotate")
    );

    public static synchronized void register(String symbol) {
        if (symbol != null && !symbol.isBlank()) SYMBOLS.add(symbol.trim());
    }

    public static synchronized void registerMembers(String module, List<String> members) {
        ensureSeeded();
        if (module == null || module.isBlank() || members == null || members.isEmpty()) return;
        List<String> target = MEMBERS.computeIfAbsent(module.trim(), k -> new ArrayList<>());
        for (String member : members) {
            if (member != null && !member.isBlank() && !target.contains(member.trim())) {
                target.add(member.trim());
            }
        }
    }

    public static synchronized void applyManifest(Set<String> globals,
                                                  Map<String, List<String>> globalMembers,
                                                  Map<String, List<String>> peripheralTypeMembers) {
        ensureSeeded();
        if (globals != null) {
            for (String global : globals) {
                if (global == null || global.isBlank()) continue;
                String trimmed = global.trim();
                SYMBOLS.add(trimmed);
                DYNAMIC_GLOBALS.add(trimmed);
            }
        }
        replaceAll(globalMembers);
        replaceAll(peripheralTypeMembers);
        if (globalMembers != null) {
            for (String key : globalMembers.keySet()) {
                if (key == null || key.isBlank()) continue;
                String trimmed = key.trim();
                if (trimmed.indexOf('/') >= 0) continue;
                SYMBOLS.add(trimmed);
            }
        }
    }

    public static synchronized Set<String> dynamicGlobals() {
        return Collections.unmodifiableSet(DYNAMIC_GLOBALS);
    }

    public static synchronized boolean isItemNamespace(String name) {
        return name != null && ITEM_NAMESPACES.contains(name);
    }

    private static void replaceAll(Map<String, List<String>> source) {
        if (source == null) return;
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String key = entry.getKey();
            List<String> incoming = entry.getValue();
            if (key == null || key.isBlank() || incoming == null || incoming.isEmpty()) continue;
            List<String> cleaned = new ArrayList<>(incoming.size());
            for (String member : incoming) {
                if (member == null || member.isBlank()) continue;
                String trimmed = member.trim();
                if (!cleaned.contains(trimmed)) cleaned.add(trimmed);
            }
            if (!cleaned.isEmpty()) MEMBERS.put(key.trim(), cleaned);
        }
    }

    public static Set<String> getAll() {
        ensureSeeded();
        return Collections.unmodifiableSet(SYMBOLS);
    }

    public static List<String> getMembers(String module) {
        ensureSeeded();
        return MEMBERS.getOrDefault(module, List.of());
    }

    private static synchronized void ensureSeeded() {
        if (seeded) return;
        seeded = true;
        List<String> allItemStrings = BuiltInRegistries.ITEM
        .keySet()
        .stream()
        .map(ResourceLocation::toString)
        .toList();

        Map<String, List<String>> blockNames = new LinkedHashMap<>();
        for (String item : allItemStrings) {
        String[] parts = item.split(":");
        String namespace = parts[0]; 
        String itemName = parts[1];  

        blockNames.computeIfAbsent(namespace, k -> new ArrayList<>()).add(itemName);
        }
        MEMBERS.putAll(blockNames);
        ITEM_NAMESPACES.addAll(blockNames.keySet());
        MEMBERS.putAll(STD_MEMBERS);

        for (String sym : WikiRegistry.getSymbols()) {
            String key = WikiRegistry.resolveSymbol(sym);
            if (key != null && CODE_PREFIXES.stream().anyMatch(key::startsWith))
                SYMBOLS.add(sym);
        }
        
        for (String sym : blockNames.keySet()) {
            SYMBOLS.add(sym);
        }

        try (InputStream is = LuaCompletionRegistry.class.getClassLoader()
                .getResourceAsStream("data/cide/wiki/members.json")) {
            if (is != null) {
                JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    List<String> list = new ArrayList<>();
                    for (JsonElement el : e.getValue().getAsJsonArray()) list.add(el.getAsString());
                    List<String> existing = MEMBERS.get(e.getKey());
                    if (existing == null || list.size() > existing.size())
                        MEMBERS.put(e.getKey(), Collections.unmodifiableList(list));
                }
            }
        } catch (Exception ignored) {}

        for (Map.Entry<String, List<String>> e : WikiRegistry.getMembers().entrySet()) {
            register(e.getKey());
            registerMembers(e.getKey(), e.getValue());
        }
    }
}
