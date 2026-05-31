package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SessionSavePayload(BlockPos pos, int computerId, String json) implements CustomPacketPayload {
    public static final Type<SessionSavePayload> TYPE = new Type<>(CideMod.id("session_save"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SessionSavePayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, SessionSavePayload::pos,
        ByteBufCodecs.VAR_INT, SessionSavePayload::computerId,
        ByteBufCodecs.STRING_UTF8, SessionSavePayload::json,
        SessionSavePayload::new
    );

    @Override
    public Type<SessionSavePayload> type() {
        return TYPE;
    }
}
