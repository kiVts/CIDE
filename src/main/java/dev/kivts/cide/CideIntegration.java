package dev.kivts.cide;

import dev.kivts.cide.client.LuaCompletionRegistry;
import dev.kivts.cide.wiki.WikiRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Public integration API for mods which want to add CIDE autocomplete and wiki
 * entries. Depend on CIDE with compileOnly and call this from a tiny optional
 * compat class after checking that the cide mod is loaded.
 */
public final class CideIntegration {
    private CideIntegration() {
    }

    public static void registerModule(String symbol, String wikiText, String... members) {
        registerModule(symbol, wikiText, Arrays.asList(members));
    }

    public static void registerModule(String symbol, String wikiText, Collection<String> members) {
        register(symbol, "module/" + symbol, wikiText, members);
    }

    public static void registerModuleFromResource(Class<?> owner, String symbol, String resourcePath, String... members) {
        registerModuleFromResource(owner, symbol, resourcePath, Arrays.asList(members));
    }

    public static void registerModuleFromResource(Class<?> owner, String symbol, String resourcePath, Collection<String> members) {
        registerModule(symbol, readResource(owner, resourcePath), members);
    }

    public static void registerPeripheral(String type, String wikiText, String... members) {
        registerPeripheral(type, wikiText, Arrays.asList(members));
    }

    public static void registerPeripheral(String type, String wikiText, Collection<String> members) {
        register(type, "peripheral/" + type, wikiText, members);
    }

    public static void registerPeripheralFromResource(Class<?> owner, String type, String resourcePath, String... members) {
        registerPeripheralFromResource(owner, type, resourcePath, Arrays.asList(members));
    }

    public static void registerPeripheralFromResource(Class<?> owner, String type, String resourcePath, Collection<String> members) {
        registerPeripheral(type, readResource(owner, resourcePath), members);
    }

    public static void register(String symbol, String pageKey, String wikiText, String... members) {
        register(symbol, pageKey, wikiText, Arrays.asList(members));
    }

    public static void register(String symbol, String pageKey, String wikiText, Collection<String> members) {
        String cleanSymbol = requireId(symbol, "symbol");
        String cleanPageKey = requirePageKey(pageKey);
        String cleanWikiText = Objects.requireNonNull(wikiText, "wikiText");
        List<String> cleanMembers = members == null
            ? List.of()
            : members.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();

        LuaCompletionRegistry.register(cleanSymbol);
        if (!cleanMembers.isEmpty()) LuaCompletionRegistry.registerMembers(cleanSymbol, cleanMembers);
        WikiRegistry.registerSymbol(cleanSymbol, cleanPageKey);
        WikiRegistry.registerPage(cleanPageKey, cleanWikiText);
    }

    public static void registerFromResource(Class<?> owner, String symbol, String pageKey, String resourcePath, String... members) {
        registerFromResource(owner, symbol, pageKey, resourcePath, Arrays.asList(members));
    }

    public static void registerFromResource(Class<?> owner, String symbol, String pageKey, String resourcePath, Collection<String> members) {
        register(symbol, pageKey, readResource(owner, resourcePath), members);
    }

    private static String requireId(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(name + " cannot be blank");
        return trimmed;
    }

    private static String requirePageKey(String pageKey) {
        String trimmed = requireId(pageKey, "pageKey");
        if (!trimmed.startsWith("module/") && !trimmed.startsWith("peripheral/") && !trimmed.startsWith("generic_peripheral/")) {
            throw new IllegalArgumentException("pageKey must start with module/, peripheral/, or generic_peripheral/");
        }
        return trimmed;
    }

    private static String readResource(Class<?> owner, String resourcePath) {
        Objects.requireNonNull(owner, "owner");
        String cleanPath = requireId(resourcePath, "resourcePath");
        try (var stream = owner.getResourceAsStream(cleanPath)) {
            if (stream == null) throw new IllegalArgumentException("Missing CIDE wiki resource: " + cleanPath);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read CIDE wiki resource: " + cleanPath, e);
        }
    }
}
