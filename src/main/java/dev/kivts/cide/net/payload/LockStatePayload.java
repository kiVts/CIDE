package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record LockStatePayload(int computerId, boolean lockedToPlayer) implements CustomPacketPayload {
    public static final Type<LockStatePayload> TYPE = new Type<>(CideMod.id("lock_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LockStatePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, LockStatePayload::computerId,
        ByteBufCodecs.BOOL, LockStatePayload::lockedToPlayer,
        LockStatePayload::new
    );

    @Override
    public Type<LockStatePayload> type() {
        return TYPE;
    }
}
