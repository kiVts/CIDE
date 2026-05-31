package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SessionLoadPayload(int computerId, String json) implements CustomPacketPayload {
    public static final Type<SessionLoadPayload> TYPE = new Type<>(CideMod.id("session_load"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SessionLoadPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, SessionLoadPayload::computerId,
        ByteBufCodecs.STRING_UTF8, SessionLoadPayload::json,
        SessionLoadPayload::new
    );

    @Override
    public Type<SessionLoadPayload> type() {
        return TYPE;
    }
}
