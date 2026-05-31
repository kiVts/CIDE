package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

import java.util.LinkedHashMap;
import java.util.Map;

public record PeripheralMapPayload(int computerId, Map<String, String> sideToType) implements CustomPacketPayload {
    public static final Type<PeripheralMapPayload> TYPE = new Type<>(CideMod.id("peripheral_map"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PeripheralMapPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PeripheralMapPayload decode(RegistryFriendlyByteBuf buf) {
            int computerId = buf.readVarInt();
            int size = buf.readVarInt();
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) map.put(buf.readUtf(), buf.readUtf());
            return new PeripheralMapPayload(computerId, map);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PeripheralMapPayload value) {
            buf.writeVarInt(value.computerId());
            buf.writeVarInt(value.sideToType().size());
            for (Map.Entry<String, String> e : value.sideToType().entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeUtf(e.getValue());
            }
        }
    };

    @Override public Type<PeripheralMapPayload> type() { return TYPE; }
}
