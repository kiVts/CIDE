package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record CopyFilePayload(BlockPos pos, int computerId, String sourcePath, String destinationDirectory) implements CustomPacketPayload {
    public static final Type<CopyFilePayload> TYPE = new Type<>(CideMod.id("copy_file"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CopyFilePayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, CopyFilePayload::pos,
        ByteBufCodecs.VAR_INT, CopyFilePayload::computerId,
        ByteBufCodecs.STRING_UTF8, CopyFilePayload::sourcePath,
        ByteBufCodecs.STRING_UTF8, CopyFilePayload::destinationDirectory,
        CopyFilePayload::new
    );

    @Override
    public Type<CopyFilePayload> type() {
        return TYPE;
    }
}
