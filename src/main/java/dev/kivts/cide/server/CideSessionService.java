package dev.kivts.cide.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kivts.cide.net.payload.SessionLoadChunkPayload;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.filesystem.MountConstants;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.shared.config.ConfigSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CideSessionService {
    private static final Gson GSON = new Gson();
    private static final String SESSION_ROOT = "cide/sessions";
    private static final int MAX_SESSION_BYTES = 64 * 1024;
    private static final int MAX_JSON_CHARS = 60 * 1024;
    private static final int SESSION_CHUNK_SIZE = 32 * 1024;
    private static final int MAX_SESSION_CHUNKS = 4;
    private static final Map<UUID, PendingSessionUpload> PENDING = new ConcurrentHashMap<>();

    private record PendingSessionUpload(BlockPos pos, int computerId, byte[][] chunks) {}

    private CideSessionService() {}

    public static void send(ServerPlayer player, int computerId) {
        String json = "";
        try {
            WritableMount mount = sessionMount(player);
            String path = sessionPath(player);
            if (mount.exists(path) && !mount.isDirectory(path)) {
                long size = mount.getSize(path);
                if (size > 0 && size <= MAX_SESSION_BYTES) {
                    String loaded = read(mount, path, (int) size);
                    JsonObject object = JsonParser.parseString(loaded).getAsJsonObject();
                    if (object.has("computerId") && object.get("computerId").getAsInt() == computerId) {
                        json = loaded;
                    } else {
                        mount.delete(path);
                    }
                } else {
                    mount.delete(path);
                }
            }
        } catch (Exception ignored) {
            json = "";
        }
        sendSession(player, computerId, json);
    }

    public static void save(ServerPlayer player, BlockPos pos, int computerId, String json) {
        saveJson(player, pos, computerId, json);
    }

    public static void saveChunk(ServerPlayer player, BlockPos pos, int computerId,
                                 int chunkIndex, int totalChunks, byte[] data) {
        if (chunkIndex == 0) {
            if (totalChunks < 1 || totalChunks > MAX_SESSION_CHUNKS) return;
            PENDING.put(player.getUUID(), new PendingSessionUpload(pos, computerId, new byte[totalChunks][]));
        }

        PendingSessionUpload upload = PENDING.get(player.getUUID());
        if (upload == null || upload.computerId() != computerId || upload.chunks().length != totalChunks
                || chunkIndex < 0 || chunkIndex >= totalChunks) {
            PENDING.remove(player.getUUID());
            return;
        }

        upload.chunks()[chunkIndex] = data;
        if (chunkIndex < totalChunks - 1) return;

        PENDING.remove(player.getUUID());
        int totalBytes = 0;
        for (byte[] chunk : upload.chunks()) {
            if (chunk == null) return;
            totalBytes += chunk.length;
            if (totalBytes > MAX_SESSION_BYTES) return;
        }

        byte[] bytes = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : upload.chunks()) {
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            offset += chunk.length;
        }
        saveJson(player, upload.pos(), upload.computerId(), new String(bytes, StandardCharsets.UTF_8));
    }

    private static void saveJson(ServerPlayer player, BlockPos pos, int computerId, String json) {
        String denied = CideAccess.validateLiveTarget(player, pos, computerId);
        if (!denied.isEmpty()) return;
        if (json == null || json.isBlank()) return;
        if (json.length() > MAX_JSON_CHARS) return;

        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            if (!object.has("computerId") || object.get("computerId").getAsInt() != computerId) return;
            byte[] bytes = GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_SESSION_BYTES) return;

            WritableMount mount = sessionMount(player);
            try (var channel = mount.openFile(sessionPath(player), MountConstants.WRITE_OPTIONS)) {
                channel.write(ByteBuffer.wrap(bytes));
            }
        } catch (Exception ignored) {
        }
    }

    private static void sendSession(ServerPlayer player, int computerId, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int totalChunks = Math.max(1, (bytes.length + SESSION_CHUNK_SIZE - 1) / SESSION_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * SESSION_CHUNK_SIZE;
            byte[] chunk = Arrays.copyOfRange(bytes, start, Math.min(start + SESSION_CHUNK_SIZE, bytes.length));
            PacketDistributor.sendToPlayer(player, new SessionLoadChunkPayload(computerId, i, totalChunks, chunk));
        }
    }

    private static WritableMount sessionMount(ServerPlayer player) {
        return ComputerCraftAPI.createSaveDirMount(player.server, SESSION_ROOT,
            Math.max(1024 * 1024, ConfigSpec.computerSpaceLimit.get()));
    }

    private static String sessionPath(ServerPlayer player) {
        return player.getUUID() + ".json";
    }

    private static String read(WritableMount mount, String path, int size) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        try (var channel = mount.openForRead(path)) {
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
        }
        buffer.flip();
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }
}
