package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record OpenIdePayload(BlockPos pos, int computerId, String label, boolean writesEnabled,
                             boolean lockedToPlayer, UUID sessionId, boolean adminView) implements CustomPacketPayload {
    public static final Type<OpenIdePayload> TYPE = new Type<>(CideMod.id("open_ide"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenIdePayload> STREAM_CODEC = StreamCodec.of(
        OpenIdePayload::write,
        OpenIdePayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, OpenIdePayload p) {
        BlockPos.STREAM_CODEC.encode(buf, p.pos);
        buf.writeVarInt(p.computerId);
        buf.writeUtf(p.label, 256);
        buf.writeBoolean(p.writesEnabled);
        buf.writeBoolean(p.lockedToPlayer);
        UUIDUtil.STREAM_CODEC.encode(buf, p.sessionId);
        buf.writeBoolean(p.adminView);
    }

    private static OpenIdePayload read(RegistryFriendlyByteBuf buf) {
        return new OpenIdePayload(
            BlockPos.STREAM_CODEC.decode(buf),
            buf.readVarInt(),
            buf.readUtf(256),
            buf.readBoolean(),
            buf.readBoolean(),
            UUIDUtil.STREAM_CODEC.decode(buf),
            buf.readBoolean()
        );
    }

    @Override
    public Type<OpenIdePayload> type() {
        return TYPE;
    }
}
