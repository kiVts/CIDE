package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record DeleteFilePayload(BlockPos pos, int computerId, String path) implements CustomPacketPayload {
    public static final Type<DeleteFilePayload> TYPE = new Type<>(CideMod.id("delete_file"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteFilePayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, DeleteFilePayload::pos,
        ByteBufCodecs.VAR_INT, DeleteFilePayload::computerId,
        ByteBufCodecs.STRING_UTF8, DeleteFilePayload::path,
        DeleteFilePayload::new
    );

    @Override
    public Type<DeleteFilePayload> type() {
        return TYPE;
    }
}
