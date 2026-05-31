package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ConsolePollPayload(BlockPos pos, int computerId, UUID sessionId) implements CustomPacketPayload {
    public static final Type<ConsolePollPayload> TYPE = new Type<>(CideMod.id("console_poll"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConsolePollPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ConsolePollPayload::pos,
        ByteBufCodecs.VAR_INT, ConsolePollPayload::computerId,
        UUIDUtil.STREAM_CODEC, ConsolePollPayload::sessionId,
        ConsolePollPayload::new
    );

    @Override
    public Type<ConsolePollPayload> type() {
        return TYPE;
    }
}
