package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ConsoleInputPayload(
    BlockPos pos, int computerId, UUID sessionId,
    int action, int a, int b, int x, int y, String text
) implements CustomPacketPayload {
    public static final int KEY_DOWN = 0;
    public static final int KEY_UP = 1;
    public static final int CHAR = 2;
    public static final int PASTE = 3;
    public static final int MOUSE_CLICK = 4;
    public static final int MOUSE_UP = 5;
    public static final int MOUSE_DRAG = 6;
    public static final int MOUSE_SCROLL = 7;

    public static final Type<ConsoleInputPayload> TYPE = new Type<>(CideMod.id("console_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleInputPayload> STREAM_CODEC = StreamCodec.of(
        ConsoleInputPayload::write,
        ConsoleInputPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, ConsoleInputPayload p) {
        BlockPos.STREAM_CODEC.encode(buf, p.pos);
        buf.writeVarInt(p.computerId);
        UUIDUtil.STREAM_CODEC.encode(buf, p.sessionId);
        buf.writeVarInt(p.action);
        buf.writeVarInt(p.a);
        buf.writeVarInt(p.b);
        buf.writeVarInt(p.x);
        buf.writeVarInt(p.y);
        buf.writeUtf(p.text, 8192);
    }

    private static ConsoleInputPayload read(RegistryFriendlyByteBuf buf) {
        return new ConsoleInputPayload(
            BlockPos.STREAM_CODEC.decode(buf),
            buf.readVarInt(),
            UUIDUtil.STREAM_CODEC.decode(buf),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readUtf(8192)
        );
    }

    @Override
    public Type<ConsoleInputPayload> type() {
        return TYPE;
    }
}
