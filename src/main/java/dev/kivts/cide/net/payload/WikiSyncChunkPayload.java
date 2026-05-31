package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** One chunk of the datapack-contributed wiki/autocomplete blob, server to client. */
public record WikiSyncChunkPayload(
        int chunkIndex, int totalChunks, byte[] data
) implements CustomPacketPayload {

    public static final Type<WikiSyncChunkPayload> TYPE = new Type<>(CideMod.id("wiki_sync_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WikiSyncChunkPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT,    WikiSyncChunkPayload::chunkIndex,
            ByteBufCodecs.VAR_INT,    WikiSyncChunkPayload::totalChunks,
            ByteBufCodecs.BYTE_ARRAY, WikiSyncChunkPayload::data,
            WikiSyncChunkPayload::new
        );

    @Override
    public Type<WikiSyncChunkPayload> type() { return TYPE; }
}
