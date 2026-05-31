package dev.kivts.cide.net.payload;

import dev.kivts.cide.CideMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record FileListPayload(int computerId, String path, List<Entry> entries, boolean truncated, long freeSpace)
        implements CustomPacketPayload {

    public static final Type<FileListPayload> TYPE = new Type<>(CideMod.id("file_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FileListPayload> STREAM_CODEC = StreamCodec.of(
        FileListPayload::write,
        FileListPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, FileListPayload p) {
        buf.writeVarInt(p.computerId);
        buf.writeUtf(p.path);
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeUtf(e.path);
            buf.writeBoolean(e.directory);
            buf.writeVarLong(e.size);
            buf.writeBoolean(e.readOnly);
        }
        buf.writeBoolean(p.truncated);
        buf.writeVarLong(p.freeSpace);
    }

    private static FileListPayload read(RegistryFriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        String path = buf.readUtf();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readUtf(), buf.readBoolean(), buf.readVarLong(), buf.readBoolean()));
        }
        boolean truncated = buf.readBoolean();
        long freeSpace = buf.readVarLong();
        return new FileListPayload(computerId, path, entries, truncated, freeSpace);
    }

    @Override
    public Type<FileListPayload> type() {
        return TYPE;
    }

    public record Entry(String path, boolean directory, long size, boolean readOnly) {}
}
