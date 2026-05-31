package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record OperationResultPayload(boolean ok, String message, String path) implements CustomPacketPayload {
    public static final Type<OperationResultPayload> TYPE = new Type<>(CideMod.id("operation_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OperationResultPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, OperationResultPayload::ok,
        ByteBufCodecs.STRING_UTF8, OperationResultPayload::message,
        ByteBufCodecs.STRING_UTF8, OperationResultPayload::path,
        OperationResultPayload::new
    );

    @Override
    public Type<OperationResultPayload> type() {
        return TYPE;
    }
}
