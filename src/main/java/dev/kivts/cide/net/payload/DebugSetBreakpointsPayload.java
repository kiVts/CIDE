package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DebugSetBreakpointsPayload(int computerId, Map<String, List<Integer>> breakpoints) implements CustomPacketPayload {

    public static final Type<DebugSetBreakpointsPayload> TYPE = new Type<>(CideMod.id("debug_set_breakpoints"));

    private static final int MAX_FILES = 256;
    private static final int MAX_LINES_PER_FILE = 4096;
    private static final int MAX_PATH_LEN = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugSetBreakpointsPayload> STREAM_CODEC = StreamCodec.of(
        DebugSetBreakpointsPayload::write,
        DebugSetBreakpointsPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, DebugSetBreakpointsPayload payload) {
        buf.writeVarInt(payload.computerId);
        int fileCount = Math.min(payload.breakpoints.size(), MAX_FILES);
        buf.writeVarInt(fileCount);
        int filesWritten = 0;
        for (Map.Entry<String, List<Integer>> entry : payload.breakpoints.entrySet()) {
            if (filesWritten >= fileCount) break;
            buf.writeUtf(safe(entry.getKey()), MAX_PATH_LEN);
            List<Integer> lines = entry.getValue();
            int lineCount = Math.min(lines == null ? 0 : lines.size(), MAX_LINES_PER_FILE);
            buf.writeVarInt(lineCount);
            if (lines != null) {
                int linesWritten = 0;
                for (Integer line : lines) {
                    if (linesWritten >= lineCount) break;
                    buf.writeVarInt(line == null ? 0 : Math.max(0, line));
                    linesWritten++;
                }
            }
            filesWritten++;
        }
    }

    private static DebugSetBreakpointsPayload read(RegistryFriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        int fileCount = buf.readVarInt();
        reject(fileCount, MAX_FILES, "files");
        Map<String, List<Integer>> breakpoints = new LinkedHashMap<>(Math.min(fileCount, 16));
        for (int i = 0; i < fileCount; i++) {
            String file = buf.readUtf(MAX_PATH_LEN);
            int lineCount = buf.readVarInt();
            reject(lineCount, MAX_LINES_PER_FILE, "lines");
            List<Integer> lines = new ArrayList<>(Math.min(lineCount, 32));
            for (int j = 0; j < lineCount; j++) lines.add(buf.readVarInt());
            breakpoints.put(file, lines);
        }
        return new DebugSetBreakpointsPayload(computerId, breakpoints);
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() > MAX_PATH_LEN ? value.substring(0, MAX_PATH_LEN) : value;
    }

    private static void reject(int claimed, int cap, String what) {
        if (claimed < 0 || claimed > cap) throw new DecoderException("Breakpoint " + what + " count out of range: " + claimed);
    }

    @Override public Type<DebugSetBreakpointsPayload> type() { return TYPE; }
}
