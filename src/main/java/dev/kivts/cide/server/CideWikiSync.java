package dev.kivts.cide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kivts.cide.net.payload.WikiSyncChunkPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * Collects CIDE wiki/autocomplete entries contributed by datapacks (files at
 * {@code data/<namespace>/cide/wiki/*.json}, the same format mods use) and syncs
 * them to clients. Collection happens once per datapack reload into a single
 * size-capped blob; sending is just slicing that cached blob, so it cannot
 * overload the server or the network.
 */
public final class CideWikiSync {
    private CideWikiSync() {}

    private static final int CHUNK_SIZE        = 32 * 1024;
    private static final int MAX_TOTAL_BYTES   = 256 * 1024; // blob ceiling => at most 8 chunks
    private static final int MAX_ENTRY_BYTES   = 128 * 1024; // skip oversized source files
    private static final int MAX_ENTRIES       = 512;
    private static final String WIKI_DIR       = "cide/wiki";

    /** Cached UTF-8 JSON array of normalized entries. Empty array when nothing was found. */
    private static volatile byte[] cachedBlob = new byte[0];

    public static final ResourceManagerReloadListener RELOAD_LISTENER = CideWikiSync::reload;

    private static void reload(ResourceManager resources) {
        JsonArray collected = new JsonArray();
        int totalBytes = 0;

        Map<ResourceLocation, Resource> files =
            resources.listResources(WIKI_DIR, loc -> loc.getPath().endsWith(".json"));

        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            if (collected.size() >= MAX_ENTRIES) break;
            ResourceLocation location = file.getKey();
            try (InputStream in = file.getValue().open()) {
                byte[] raw = in.readAllBytes();
                if (raw.length > MAX_ENTRY_BYTES) continue;
                JsonElement parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
                String defaultSymbol = baseName(location);
                for (JsonObject entry : extractEntries(parsed)) {
                    if (collected.size() >= MAX_ENTRIES) break;
                    if (!normalizeEntry(entry, defaultSymbol, location, resources)) continue;
                    int size = entry.toString().getBytes(StandardCharsets.UTF_8).length;
                    if (totalBytes + size > MAX_TOTAL_BYTES) {
                        cachedBlob = collected.toString().getBytes(StandardCharsets.UTF_8);
                        broadcast();
                        return;
                    }
                    collected.add(entry);
                    totalBytes += size;
                }
            } catch (Exception ignored) {
                // Skip malformed/unreadable datapack files instead of failing the reload.
            }
        }

        cachedBlob = collected.toString().getBytes(StandardCharsets.UTF_8);
        broadcast();
    }

    /** Mirrors WikiRegistry's mod-file parsing: top-level array, {"entries":[...]}, or a single object. */
    private static java.util.List<JsonObject> extractEntries(JsonElement root) {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                if (el.isJsonObject()) out.add(el.getAsJsonObject());
            }
        } else if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            JsonElement entries = obj.get("entries");
            if (entries != null && entries.isJsonArray()) {
                for (JsonElement el : entries.getAsJsonArray()) {
                    if (el.isJsonObject()) out.add(el.getAsJsonObject());
                }
            } else {
                out.add(obj);
            }
        }
        return out;
    }

    /**
     * Ensures the entry has a symbol and a valid pageKey, and inlines any sibling
     * {@code .md} wiki text so the client receives a self-contained object.
     * Returns false if the entry should be dropped.
     */
    private static boolean normalizeEntry(JsonObject entry, String defaultSymbol,
                                          ResourceLocation source, ResourceManager resources) {
        String symbol = entry.has("symbol") ? entry.get("symbol").getAsString().trim() : defaultSymbol;
        if (symbol.isEmpty()) return false;
        entry.addProperty("symbol", symbol);

        String pageKey = entry.has("pageKey") ? entry.get("pageKey").getAsString().trim() : "";
        if (pageKey.isEmpty()) {
            String kind = entry.has("kind") ? entry.get("kind").getAsString().trim() : "module";
            pageKey = kind + "/" + symbol;
        }
        if (!pageKey.startsWith("module/") && !pageKey.startsWith("peripheral/")
                && !pageKey.startsWith("generic_peripheral/")) {
            return false;
        }
        entry.addProperty("pageKey", pageKey);

        if (!entry.has("wiki") && !entry.has("page")) {
            String sibling = readSibling(source, resources);
            if (!sibling.isEmpty()) entry.addProperty("wiki", sibling);
        }
        return true;
    }

    private static String readSibling(ResourceLocation jsonLocation, ResourceManager resources) {
        String path = jsonLocation.getPath();
        if (!path.endsWith(".json")) return "";
        ResourceLocation md = ResourceLocation.fromNamespaceAndPath(
            jsonLocation.getNamespace(), path.substring(0, path.length() - 5) + ".md");
        try {
            Resource resource = resources.getResource(md).orElse(null);
            if (resource == null) return "";
            try (InputStream in = resource.open()) {
                byte[] raw = in.readAllBytes();
                if (raw.length > MAX_ENTRY_BYTES) return "";
                return new String(raw, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String baseName(ResourceLocation location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    private static void broadcast() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player);
    }

    /** Sends the cached datapack wiki blob to one player as size-bounded chunks. */
    public static void send(ServerPlayer player) {
        byte[] blob = cachedBlob;
        if (blob.length == 0) return;
        int totalChunks = (blob.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            byte[] chunk = Arrays.copyOfRange(blob, start, Math.min(start + CHUNK_SIZE, blob.length));
            PacketDistributor.sendToPlayer(player, new WikiSyncChunkPayload(i, totalChunks, chunk));
        }
    }
}
