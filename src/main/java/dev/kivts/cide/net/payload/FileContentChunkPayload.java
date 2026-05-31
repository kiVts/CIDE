package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FileContentChunkPayload(
        int computerId, String path,
        int chunkIndex, int totalChunks, byte[] data,
        boolean readOnly
) implements CustomPacketPayload {

    public static final Type<FileContentChunkPayload> TYPE = new Type<>(CideMod.id("file_content_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FileContentChunkPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT,     FileContentChunkPayload::computerId,
            ByteBufCodecs.STRING_UTF8, FileContentChunkPayload::path,
            ByteBufCodecs.VAR_INT,     FileContentChunkPayload::chunkIndex,
            ByteBufCodecs.VAR_INT,     FileContentChunkPayload::totalChunks,
            ByteBufCodecs.BYTE_ARRAY,  FileContentChunkPayload::data,
            ByteBufCodecs.BOOL,        FileContentChunkPayload::readOnly,
            FileContentChunkPayload::new
        );

    @Override
    public Type<FileContentChunkPayload> type() { return TYPE; }
}
