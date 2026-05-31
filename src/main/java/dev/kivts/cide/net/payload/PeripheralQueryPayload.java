package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PeripheralQueryPayload(BlockPos pos, int computerId) implements CustomPacketPayload {
    public static final Type<PeripheralQueryPayload> TYPE = new Type<>(CideMod.id("peripheral_query"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PeripheralQueryPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC,  PeripheralQueryPayload::pos,
        ByteBufCodecs.VAR_INT,  PeripheralQueryPayload::computerId,
        PeripheralQueryPayload::new
    );

    @Override public Type<PeripheralQueryPayload> type() { return TYPE; }
}
