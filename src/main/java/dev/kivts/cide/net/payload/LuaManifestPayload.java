package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
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

    public static final StreamCodec<RegistryFriendlyByteBuf, LuaManifestPayload> STREAM_CODEC = StreamCodec.of(
        LuaManifestPayload::write,
        LuaManifestPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, LuaManifestPayload payload) {
        writeStringSet(buf, payload.globals);
        writeStringListMap(buf, payload.globalMembers);
        writeStringListMap(buf, payload.peripheralTypeMembers);
    }

    private static LuaManifestPayload read(RegistryFriendlyByteBuf buf) {
        Set<String> globals = readStringSet(buf);
        Map<String, List<String>> globalMembers = readStringListMap(buf);
        Map<String, List<String>> peripheralTypeMembers = readStringListMap(buf);
        return new LuaManifestPayload(globals, globalMembers, peripheralTypeMembers);
    }

    private static void writeStringSet(RegistryFriendlyByteBuf buf, Set<String> set) {
        buf.writeVarInt(set.size());
        for (String value : set) buf.writeUtf(value, 256);
    }

    private static Set<String> readStringSet(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Set<String> set = new LinkedHashSet<>(Math.max(16, size));
        for (int i = 0; i < size; i++) set.add(buf.readUtf(256));
        return set;
    }

    private static void writeStringListMap(RegistryFriendlyByteBuf buf, Map<String, List<String>> map) {
        buf.writeVarInt(map.size());
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            buf.writeUtf(entry.getKey(), 256);
            List<String> values = entry.getValue();
            buf.writeVarInt(values.size());
            for (String value : values) buf.writeUtf(value, 256);
        }
    }

    private static Map<String, List<String>> readStringListMap(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, List<String>> map = new LinkedHashMap<>(Math.max(16, size));
        for (int i = 0; i < size; i++) {
            String key = buf.readUtf(256);
            int count = buf.readVarInt();
            List<String> values = new ArrayList<>(count);
            for (int j = 0; j < count; j++) values.add(buf.readUtf(256));
            map.put(key, values);
        }
        return map;
    }

    @Override
    public Type<LuaManifestPayload> type() {
        return TYPE;
    }
}
