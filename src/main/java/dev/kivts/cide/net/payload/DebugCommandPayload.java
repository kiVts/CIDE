package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record DebugCommandPayload(int computerId, int command) implements CustomPacketPayload {

    public static final int CONTINUE = 0;
    public static final int STEP     = 1;

    public static final Type<DebugCommandPayload> TYPE = new Type<>(CideMod.id("debug_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugCommandPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, DebugCommandPayload::computerId,
        ByteBufCodecs.VAR_INT, DebugCommandPayload::command,
        DebugCommandPayload::new
    );

    @Override public Type<DebugCommandPayload> type() { return TYPE; }
}
