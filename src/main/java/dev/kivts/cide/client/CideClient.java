package dev.kivts.cide.client;

import dev.kivts.cide.net.payload.FileContentPayload;
import dev.kivts.cide.net.payload.FileContentChunkPayload;
import dev.kivts.cide.net.payload.FileListPayload;
import dev.kivts.cide.net.payload.LockStatePayload;
import dev.kivts.cide.net.payload.LuaManifestPayload;
import dev.kivts.cide.net.payload.OpenIdePayload;
import dev.kivts.cide.net.payload.OperationResultPayload;
import dev.kivts.cide.net.payload.PeripheralMapPayload;
import dev.kivts.cide.net.payload.SessionLoadPayload;
import dev.kivts.cide.net.payload.SessionLoadChunkPayload;
import dev.kivts.cide.net.payload.ConsoleStatePayload;
import dev.kivts.cide.net.payload.WikiSyncChunkPayload;
import dev.kivts.cide.wiki.WikiRegistry;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class CideClient {
    private CideClient() {
    }

    // Datapack wiki sync: bounded reassembly buffer. Caps mirror the server-side limits.
    private static final int WIKI_SYNC_MAX_CHUNKS = 16;
    private static final int WIKI_SYNC_MAX_BYTES  = 256 * 1024;
    private static byte[][] wikiSyncChunks;

    public static void open(OpenIdePayload payload) {
        Minecraft.getInstance().setScreen(new CideScreen(payload));
    }

    public static void handleFileList(FileListPayload payload) {
        CideScreen.handleFileList(payload);
    }

    public static void handleFileContent(FileContentPayload payload) {
        CideScreen.handleFileContent(payload);
    }

    public static void handleFileContentChunk(FileContentChunkPayload payload) {
        CideScreen.handleFileContentChunk(payload);
    }

    public static void handleOperation(OperationResultPayload payload) {
        CideScreen.handleOperation(payload);
    }

    public static void handlePeripheralMap(PeripheralMapPayload payload) {
        CideScreen.handlePeripheralMap(payload);
    }

    public static void handleLockState(LockStatePayload payload) {
        CideScreen.handleLockState(payload);
    }

    public static void handleSessionLoad(SessionLoadPayload payload) {
        CideScreen.handleSessionLoad(payload);
    }

    public static void handleSessionLoadChunk(SessionLoadChunkPayload payload) {
        CideScreen.handleSessionLoadChunk(payload);
    }

    public static void handleConsoleState(ConsoleStatePayload payload) {
        CideScreen.handleConsoleState(payload);
    }

    public static void handleLuaManifest(LuaManifestPayload payload) {
        LuaCompletionRegistry.applyManifest(payload.globals(), payload.globalMembers(), payload.peripheralTypeMembers());
    }

    public static void handleWikiSyncChunk(WikiSyncChunkPayload payload) {
        int total = payload.totalChunks();
        int index = payload.chunkIndex();
        if (total < 1 || total > WIKI_SYNC_MAX_CHUNKS) return;
        if (index < 0 || index >= total) return;

        if (wikiSyncChunks == null || wikiSyncChunks.length != total) {
            wikiSyncChunks = new byte[total][];
        }
        wikiSyncChunks[index] = payload.data();

        int totalBytes = 0;
        for (byte[] chunk : wikiSyncChunks) {
            if (chunk == null) return; // still waiting for chunks
            totalBytes += chunk.length;
            if (totalBytes > WIKI_SYNC_MAX_BYTES) { wikiSyncChunks = null; return; }
        }

        byte[] blob = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : wikiSyncChunks) {
            System.arraycopy(chunk, 0, blob, offset, chunk.length);
            offset += chunk.length;
        }
        wikiSyncChunks = null;

        Map<String, List<String>> registered =
            WikiRegistry.ingestDatapackEntries(new String(blob, StandardCharsets.UTF_8));
        for (Map.Entry<String, List<String>> entry : registered.entrySet()) {
            LuaCompletionRegistry.register(entry.getKey());
            if (!entry.getValue().isEmpty()) {
                LuaCompletionRegistry.registerMembers(entry.getKey(), entry.getValue());
            }
        }
    }
}
