package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record MoveFilePayload(BlockPos pos, int computerId, String sourcePath, String destinationDirectory) implements CustomPacketPayload {
    public static final Type<MoveFilePayload> TYPE = new Type<>(CideMod.id("move_file"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MoveFilePayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, MoveFilePayload::pos,
        ByteBufCodecs.VAR_INT, MoveFilePayload::computerId,
        ByteBufCodecs.STRING_UTF8, MoveFilePayload::sourcePath,
        ByteBufCodecs.STRING_UTF8, MoveFilePayload::destinationDirectory,
        MoveFilePayload::new
    );

    @Override
    public Type<MoveFilePayload> type() {
        return TYPE;
    }
}
