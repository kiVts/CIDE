package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record LuaManifestPayload(
    Set<String> globals,
    Map<String, List<String>> globalMembers,
    Map<String, List<String>> peripheralTypeMembers
) implements CustomPacketPayload {

    public static final Type<LuaManifestPayload> TYPE = new Type<>(CideMod.id("lua_manifest"));

    private static final int MAX_STRING_LEN       = 256;
    private static final int MAX_GLOBALS          = 2048;
    private static final int MAX_MAP_ENTRIES      = 8192;
    private static final int MAX_MEMBERS_PER_KEY  = 1024;
    private static final int INITIAL_CAPACITY_CAP = 64;

    public static final StreamCodec<RegistryFriendlyByteBuf, LuaManifestPayload> STREAM_CODEC = StreamCodec.of(
        LuaManifestPayload::write,
        LuaManifestPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, LuaManifestPayload payload) {
        writeStringSet(buf, payload.globals, MAX_GLOBALS);
        writeStringListMap(buf, payload.globalMembers);
        writeStringListMap(buf, payload.peripheralTypeMembers);
    }

    private static LuaManifestPayload read(RegistryFriendlyByteBuf buf) {
        Set<String> globals = readStringSet(buf);
        Map<String, List<String>> globalMembers = readStringListMap(buf);
        Map<String, List<String>> peripheralTypeMembers = readStringListMap(buf);
        return new LuaManifestPayload(globals, globalMembers, peripheralTypeMembers);
    }

    private static void writeStringSet(RegistryFriendlyByteBuf buf, Set<String> set, int cap) {
        int size = Math.min(set.size(), cap);
        buf.writeVarInt(size);
        int written = 0;
        for (String value : set) {
            if (written >= size) break;
            buf.writeUtf(safeString(value), MAX_STRING_LEN);
            written++;
        }
    }

    private static Set<String> readStringSet(RegistryFriendlyByteBuf buf) {
        int claimed = buf.readVarInt();
        rejectIfTooLarge(claimed, MAX_GLOBALS, "globals");
        Set<String> set = new LinkedHashSet<>(Math.min(claimed, INITIAL_CAPACITY_CAP));
        for (int i = 0; i < claimed; i++) set.add(buf.readUtf(MAX_STRING_LEN));
        return set;
    }

    private static void writeStringListMap(RegistryFriendlyByteBuf buf, Map<String, List<String>> map) {
        int size = Math.min(map.size(), MAX_MAP_ENTRIES);
        buf.writeVarInt(size);
        int written = 0;
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            if (written >= size) break;
            buf.writeUtf(safeString(entry.getKey()), MAX_STRING_LEN);
            List<String> values = entry.getValue();
            int valueCount = Math.min(values == null ? 0 : values.size(), MAX_MEMBERS_PER_KEY);
            buf.writeVarInt(valueCount);
            int valueWritten = 0;
            if (values != null) {
                for (String value : values) {
                    if (valueWritten >= valueCount) break;
                    buf.writeUtf(safeString(value), MAX_STRING_LEN);
                    valueWritten++;
                }
            }
            written++;
        }
    }

    private static Map<String, List<String>> readStringListMap(RegistryFriendlyByteBuf buf) {
        int claimed = buf.readVarInt();
        rejectIfTooLarge(claimed, MAX_MAP_ENTRIES, "map entries");
        Map<String, List<String>> map = new LinkedHashMap<>(Math.min(claimed, INITIAL_CAPACITY_CAP));
        for (int i = 0; i < claimed; i++) {
            String key = buf.readUtf(MAX_STRING_LEN);
            int valueCount = buf.readVarInt();
            rejectIfTooLarge(valueCount, MAX_MEMBERS_PER_KEY, "members for '" + key + "'");
            List<String> values = new ArrayList<>(Math.min(valueCount, INITIAL_CAPACITY_CAP));
            for (int j = 0; j < valueCount; j++) values.add(buf.readUtf(MAX_STRING_LEN));
            map.put(key, values);
        }
        return map;
    }

    private static String safeString(String value) {
        if (value == null) return "";
        if (value.length() > MAX_STRING_LEN) return value.substring(0, MAX_STRING_LEN);
        return value;
    }

    private static void rejectIfTooLarge(int claimed, int cap, String what) {
        if (claimed < 0 || claimed > cap) {
            throw new DecoderException("Lua manifest " + what + " count out of range: " + claimed);
        }
    }

    @Override
    public Type<LuaManifestPayload> type() {
        return TYPE;
    }
}
