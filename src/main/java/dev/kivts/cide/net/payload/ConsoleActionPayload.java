package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ConsoleActionPayload(BlockPos pos, int computerId, UUID sessionId, int action)
        implements CustomPacketPayload {
    public static final int TERMINATE = 0;
    public static final int TURN_ON = 1;
    public static final int SHUTDOWN = 2;
    public static final int REBOOT = 3;
    public static final int CLOSE = 4;
    public static final int RELEASE_INPUTS = 5;

    public static final Type<ConsoleActionPayload> TYPE = new Type<>(CideMod.id("console_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleActionPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ConsoleActionPayload::pos,
        ByteBufCodecs.VAR_INT, ConsoleActionPayload::computerId,
        UUIDUtil.STREAM_CODEC, ConsoleActionPayload::sessionId,
        ByteBufCodecs.VAR_INT, ConsoleActionPayload::action,
        ConsoleActionPayload::new
    );

    @Override
    public Type<ConsoleActionPayload> type() {
        return TYPE;
    }
}
