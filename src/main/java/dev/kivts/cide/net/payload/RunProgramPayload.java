package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record RunProgramPayload(BlockPos pos, int computerId, UUID sessionId, String path)
        implements CustomPacketPayload {
    public static final Type<RunProgramPayload> TYPE = new Type<>(CideMod.id("run_program"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RunProgramPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, RunProgramPayload::pos,
        ByteBufCodecs.VAR_INT, RunProgramPayload::computerId,
        UUIDUtil.STREAM_CODEC, RunProgramPayload::sessionId,
        ByteBufCodecs.STRING_UTF8, RunProgramPayload::path,
        RunProgramPayload::new
    );

    @Override
    public Type<RunProgramPayload> type() {
        return TYPE;
    }
}
