package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.LinkedHashMap;
import java.util.Map;

public record DebugPausedPayload(int computerId, String file, int line, Map<String, String> locals) implements CustomPacketPayload {

    public static final Type<DebugPausedPayload> TYPE = new Type<>(CideMod.id("debug_paused"));

    private static final int MAX_LOCALS = 256;
    private static final int MAX_STRING_LEN = 256;
    private static final int MAX_VALUE_LEN = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugPausedPayload> STREAM_CODEC = StreamCodec.of(
        DebugPausedPayload::write,
        DebugPausedPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, DebugPausedPayload payload) {
        buf.writeVarInt(payload.computerId);
        buf.writeUtf(safe(payload.file, MAX_STRING_LEN), MAX_STRING_LEN);
        buf.writeVarInt(Math.max(0, payload.line));
        int count = Math.min(payload.locals == null ? 0 : payload.locals.size(), MAX_LOCALS);
        buf.writeVarInt(count);
        if (payload.locals != null) {
            int written = 0;
            for (Map.Entry<String, String> entry : payload.locals.entrySet()) {
                if (written >= count) break;
                buf.writeUtf(safe(entry.getKey(), MAX_STRING_LEN), MAX_STRING_LEN);
                buf.writeUtf(safe(entry.getValue(), MAX_VALUE_LEN), MAX_VALUE_LEN);
                written++;
            }
        }
    }

    private static DebugPausedPayload read(RegistryFriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        String file = buf.readUtf(MAX_STRING_LEN);
        int line = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_LOCALS) throw new DecoderException("Locals count out of range: " + count);
        Map<String, String> locals = new LinkedHashMap<>(Math.min(count, 32));
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf(MAX_STRING_LEN);
            String value = buf.readUtf(MAX_VALUE_LEN);
            locals.put(name, value);
        }
        return new DebugPausedPayload(computerId, file, line, locals);
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    @Override public Type<DebugPausedPayload> type() { return TYPE; }
}
