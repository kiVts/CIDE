package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SessionSaveChunkPayload(
        BlockPos pos, int computerId,
        int chunkIndex, int totalChunks, byte[] data
) implements CustomPacketPayload {

    public static final Type<SessionSaveChunkPayload> TYPE = new Type<>(CideMod.id("session_save_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SessionSaveChunkPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC,     SessionSaveChunkPayload::pos,
            ByteBufCodecs.VAR_INT,     SessionSaveChunkPayload::computerId,
            ByteBufCodecs.VAR_INT,     SessionSaveChunkPayload::chunkIndex,
            ByteBufCodecs.VAR_INT,     SessionSaveChunkPayload::totalChunks,
            ByteBufCodecs.BYTE_ARRAY,  SessionSaveChunkPayload::data,
            SessionSaveChunkPayload::new
        );

    @Override
    public Type<SessionSaveChunkPayload> type() { return TYPE; }
}
