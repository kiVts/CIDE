package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleLockPayload(BlockPos pos, int computerId) implements CustomPacketPayload {
    public static final Type<ToggleLockPayload> TYPE = new Type<>(CideMod.id("toggle_lock"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleLockPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ToggleLockPayload::pos,
        ByteBufCodecs.VAR_INT, ToggleLockPayload::computerId,
        ToggleLockPayload::new
    );

    @Override
    public Type<ToggleLockPayload> type() {
        return TYPE;
    }
}
