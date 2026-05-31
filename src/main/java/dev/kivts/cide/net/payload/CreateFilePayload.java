package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record CreateFilePayload(BlockPos pos, int computerId, String directory) implements CustomPacketPayload {
    public static final Type<CreateFilePayload> TYPE = new Type<>(CideMod.id("create_file"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateFilePayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, CreateFilePayload::pos,
        ByteBufCodecs.VAR_INT, CreateFilePayload::computerId,
        ByteBufCodecs.STRING_UTF8, CreateFilePayload::directory,
        CreateFilePayload::new
    );

    @Override
    public Type<CreateFilePayload> type() {
        return TYPE;
    }
}
