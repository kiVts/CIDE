package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ListFilesPayload(BlockPos pos, int computerId, String path) implements CustomPacketPayload {
    public static final Type<ListFilesPayload> TYPE = new Type<>(CideMod.id("list_files"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ListFilesPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ListFilesPayload::pos,
        ByteBufCodecs.VAR_INT, ListFilesPayload::computerId,
        ByteBufCodecs.STRING_UTF8, ListFilesPayload::path,
        ListFilesPayload::new
    );

    @Override
    public Type<ListFilesPayload> type() {
        return TYPE;
    }
}
