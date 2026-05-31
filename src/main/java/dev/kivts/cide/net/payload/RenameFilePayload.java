package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record RenameFilePayload(BlockPos pos, int computerId, String sourcePath, String newName) implements CustomPacketPayload {
    public static final Type<RenameFilePayload> TYPE = new Type<>(CideMod.id("rename_file"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RenameFilePayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, RenameFilePayload::pos,
        ByteBufCodecs.VAR_INT, RenameFilePayload::computerId,
        ByteBufCodecs.STRING_UTF8, RenameFilePayload::sourcePath,
        ByteBufCodecs.STRING_UTF8, RenameFilePayload::newName,
        RenameFilePayload::new
    );

    @Override
    public Type<RenameFilePayload> type() {
        return TYPE;
    }
}
