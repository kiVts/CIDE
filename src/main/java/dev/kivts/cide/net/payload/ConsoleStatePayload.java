package dev.kivts.cide.net.payload;

import dan200.computercraft.shared.computer.terminal.TerminalState;
import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ConsoleStatePayload(int computerId, boolean on, TerminalState terminal)
        implements CustomPacketPayload {
    public static final Type<ConsoleStatePayload> TYPE = new Type<>(CideMod.id("console_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleStatePayload> STREAM_CODEC = StreamCodec.of(
        ConsoleStatePayload::write,
        ConsoleStatePayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, ConsoleStatePayload p) {
        buf.writeVarInt(p.computerId);
        buf.writeBoolean(p.on);
        buf.writeBoolean(p.terminal != null);
        if (p.terminal != null) TerminalState.STREAM_CODEC.encode(buf, p.terminal);
    }

    private static ConsoleStatePayload read(RegistryFriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        boolean on = buf.readBoolean();
        TerminalState terminal = buf.readBoolean() ? TerminalState.STREAM_CODEC.decode(buf) : null;
        return new ConsoleStatePayload(computerId, on, terminal);
    }

    @Override
    public Type<ConsoleStatePayload> type() {
        return TYPE;
    }
}
