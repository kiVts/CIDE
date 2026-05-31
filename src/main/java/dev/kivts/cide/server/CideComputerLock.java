package dev.kivts.cide.server;

import dev.kivts.cide.net.CidePackets;
import dev.kivts.cide.net.payload.LockStatePayload;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.filesystem.MountConstants;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.shared.config.ConfigSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

public final class CideComputerLock {
    private static final String LOCK_ROOT = "cide/locks";
    private static final String LEGACY_LOCK_PATH = ".cide.lock";

    private CideComputerLock() {}

    public static boolean isLockPath(String path) {
        return LEGACY_LOCK_PATH.equals(CidePaths.sanitize(path));
    }

    public static boolean lockedToPlayer(ServerPlayer player, int computerId) {
        return owner(player, computerId).filter(player.getUUID()::equals).isPresent();
    }

    public static String validate(ServerPlayer player, int computerId) {
        Optional<UUID> owner = owner(player, computerId);
        if (owner.isPresent() && !owner.get().equals(player.getUUID())) return "CIDE is locked to another player.";
        return "";
    }

    public static void toggle(ServerPlayer player, BlockPos pos, int computerId) {
        String denied = CideAccess.validateLiveTarget(player, pos, computerId);
        if (!denied.isEmpty()) {
            CidePackets.sendDenied(player, denied);
            return;
        }

        Optional<UUID> owner = owner(player, computerId);
        try {
            WritableMount mount = lockMount(player);
            String path = lockPath(computerId);
            if (owner.isPresent()) {
                if (!owner.get().equals(player.getUUID())) {
                    CidePackets.sendDenied(player, "CIDE is locked to another player.");
                    return;
                }
                if (mount.exists(path)) mount.delete(path);
                sendState(player, computerId);
                return;
            }

            byte[] bytes = player.getUUID().toString().getBytes(StandardCharsets.UTF_8);
            try (var channel = mount.openFile(path, MountConstants.WRITE_OPTIONS)) {
                channel.write(ByteBuffer.wrap(bytes));
            }
            sendState(player, computerId);
        } catch (Exception e) {
            CidePackets.sendDenied(player, "could not update CIDE lock");
        }
    }

    public static void sendState(ServerPlayer player, int computerId) {
        PacketDistributor.sendToPlayer(player, new LockStatePayload(computerId, lockedToPlayer(player, computerId)));
    }

    private static Optional<UUID> owner(ServerPlayer player, int computerId) {
        Optional<UUID> migrated = migrateLegacyLock(player, computerId);
        if (migrated.isPresent()) return migrated;

        try {
            WritableMount mount = lockMount(player);
            String path = lockPath(computerId);
            if (!mount.exists(path) || mount.isDirectory(path)) return Optional.empty();
            long size = mount.getSize(path);
            if (size < 1 || size > 64) return Optional.empty();
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            try (var channel = mount.openForRead(path)) {
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
            }
            buffer.flip();
            String text = StandardCharsets.UTF_8.decode(buffer).toString().trim();
            return Optional.of(UUID.fromString(text));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> migrateLegacyLock(ServerPlayer player, int computerId) {
        try {
            WritableMount legacy = ComputerCraftAPI.createSaveDirMount(player.server, "computer/" + computerId,
                Math.max(0, ConfigSpec.computerSpaceLimit.get()));
            if (!legacy.exists(LEGACY_LOCK_PATH) || legacy.isDirectory(LEGACY_LOCK_PATH)) return Optional.empty();
            long size = legacy.getSize(LEGACY_LOCK_PATH);
            if (size < 1 || size > 64) {
                legacy.delete(LEGACY_LOCK_PATH);
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            try (var channel = legacy.openForRead(LEGACY_LOCK_PATH)) {
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
            }
            buffer.flip();
            UUID owner = UUID.fromString(StandardCharsets.UTF_8.decode(buffer).toString().trim());
            WritableMount current = lockMount(player);
            byte[] bytes = owner.toString().getBytes(StandardCharsets.UTF_8);
            try (var channel = current.openFile(lockPath(computerId), MountConstants.WRITE_OPTIONS)) {
                channel.write(ByteBuffer.wrap(bytes));
            }
            legacy.delete(LEGACY_LOCK_PATH);
            return Optional.of(owner);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static WritableMount lockMount(ServerPlayer player) {
        return ComputerCraftAPI.createSaveDirMount(player.server, LOCK_ROOT,
            Math.max(1024 * 1024, ConfigSpec.computerSpaceLimit.get()));
    }

    private static String lockPath(int computerId) {
        return computerId + ".lock";
    }
}
