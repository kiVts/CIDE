package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record WriteFilePayload(BlockPos pos, int computerId, String path, String content) implements CustomPacketPayload {
    public static final Type<WriteFilePayload> TYPE = new Type<>(CideMod.id("write_file"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WriteFilePayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, WriteFilePayload::pos,
        ByteBufCodecs.VAR_INT, WriteFilePayload::computerId,
        ByteBufCodecs.STRING_UTF8, WriteFilePayload::path,
        ByteBufCodecs.STRING_UTF8, WriteFilePayload::content,
        WriteFilePayload::new
    );

    @Override
    public Type<WriteFilePayload> type() {
        return TYPE;
    }
}
