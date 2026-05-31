package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record WriteChunkPayload(
        BlockPos pos, int computerId, String path,
        int chunkIndex, int totalChunks, byte[] data
) implements CustomPacketPayload {

    public static final Type<WriteChunkPayload> TYPE = new Type<>(CideMod.id("write_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WriteChunkPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC,     WriteChunkPayload::pos,
            ByteBufCodecs.VAR_INT,     WriteChunkPayload::computerId,
            ByteBufCodecs.STRING_UTF8, WriteChunkPayload::path,
            ByteBufCodecs.VAR_INT,     WriteChunkPayload::chunkIndex,
            ByteBufCodecs.VAR_INT,     WriteChunkPayload::totalChunks,
            ByteBufCodecs.BYTE_ARRAY,  WriteChunkPayload::data,
            WriteChunkPayload::new
        );

    @Override
    public Type<WriteChunkPayload> type() { return TYPE; }
}
