package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SessionLoadChunkPayload(
        int computerId,
        int chunkIndex, int totalChunks, byte[] data
) implements CustomPacketPayload {

    public static final Type<SessionLoadChunkPayload> TYPE = new Type<>(CideMod.id("session_load_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SessionLoadChunkPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT,     SessionLoadChunkPayload::computerId,
            ByteBufCodecs.VAR_INT,     SessionLoadChunkPayload::chunkIndex,
            ByteBufCodecs.VAR_INT,     SessionLoadChunkPayload::totalChunks,
            ByteBufCodecs.BYTE_ARRAY,  SessionLoadChunkPayload::data,
            SessionLoadChunkPayload::new
        );

    @Override
    public Type<SessionLoadChunkPayload> type() { return TYPE; }
}
