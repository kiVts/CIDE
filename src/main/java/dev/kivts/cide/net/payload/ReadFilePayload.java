package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ReadFilePayload(BlockPos pos, int computerId, String path) implements CustomPacketPayload {
    public static final Type<ReadFilePayload> TYPE = new Type<>(CideMod.id("read_file"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ReadFilePayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ReadFilePayload::pos,
        ByteBufCodecs.VAR_INT, ReadFilePayload::computerId,
        ByteBufCodecs.STRING_UTF8, ReadFilePayload::path,
        ReadFilePayload::new
    );

    @Override
    public Type<ReadFilePayload> type() {
        return TYPE;
    }
}
