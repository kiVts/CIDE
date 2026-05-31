package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record CreateFolderPayload(BlockPos pos, int computerId, String path) implements CustomPacketPayload {
    public static final Type<CreateFolderPayload> TYPE = new Type<>(CideMod.id("create_folder"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateFolderPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, CreateFolderPayload::pos,
        ByteBufCodecs.VAR_INT, CreateFolderPayload::computerId,
        ByteBufCodecs.STRING_UTF8, CreateFolderPayload::path,
        CreateFolderPayload::new
    );

    @Override
    public Type<CreateFolderPayload> type() {
        return TYPE;
    }
}
