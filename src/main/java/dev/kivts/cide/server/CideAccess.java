package dev.kivts.cide.server;

import dev.kivts.cide.config.CideServerConfig;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Locale;

public final class CideAccess {
    private CideAccess() {}

    public static Target resolve(ServerPlayer player, AbstractComputerBlockEntity computer) {
        if (CideServerConfig.REQUIRE_OPERATOR.get() && !player.hasPermissions(2))
            return Target.denied("operator permissions required");
        if (!computer.isUsable(player))
            return Target.denied("computer is too far away or locked");

        var serverComputer = computer.createServerComputer();
        return Target.allowed(serverComputer.getID(), serverComputer.getLabel(),
            CideServerConfig.ENABLE_WRITES.get());
    }

    /**
     * Checks access permissions for a pocket computer (no block position).
     * Returns an empty string if access is allowed, or an error message.
     */
    public static String resolvePocket(ServerPlayer player) {
        if (CideServerConfig.REQUIRE_OPERATOR.get() && !player.hasPermissions(2))
            return "operator permissions required";
        return "";
    }

    public static String validateLiveTarget(ServerPlayer player, BlockPos pos, int computerId) {
        // BlockPos.ZERO is the sentinel for pocket computers - no block to validate
        if (pos.equals(BlockPos.ZERO)) {
            String denied = resolvePocket(player);
            if (denied.isEmpty()) denied = CideComputerLock.validate(player, computerId);
            return denied;
        }
        if (!player.level().isLoaded(pos)) return "computer is not loaded";
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractComputerBlockEntity computer)) return "computer no longer exists";

        Target target = resolve(player, computer);
        if (!target.allowed()) return target.reason();
        if (target.computerId() != computerId) return "computer identity changed";
        return CideComputerLock.validate(player, computerId);
    }

    public static String validatePath(String rawPath, boolean write) {
        String path = CidePaths.sanitize(rawPath);
        if (path.isEmpty()) return "Path name cannot be empty.";
        if (CideComputerLock.isLockPath(path)) return "This name is reserved by CIDE.";
        String lower = path.toLowerCase(Locale.ROOT);

        for (String denied : CideServerConfig.DENIED_PATH_PREFIXES.get()) {
            String prefix = normalizePrefix(denied);
            if (!prefix.isEmpty() && matchesPathPrefix(lower, prefix))
                return "This name is prohibited via config.";
        }

        if (!CideServerConfig.ALLOWED_PATH_PREFIXES.get().isEmpty()) {
            boolean matched = false;
            for (String allowed : CideServerConfig.ALLOWED_PATH_PREFIXES.get()) {
                String prefix = normalizePrefix(allowed);
                if (!prefix.isEmpty() && matchesPathPrefix(lower, prefix)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return "Server config has path name whitelist - Contact server operators for clarification.";
        }

        if (write && !CideServerConfig.ENABLE_WRITES.get()) return "Writing is disabled via server config.";
        return "";
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) return "";
        String raw = prefix.replace('\\', '/').strip().toLowerCase(Locale.ROOT);
        if (raw.equals(".")) return ".";
        return CidePaths.sanitize(raw);
    }

    private static boolean matchesPathPrefix(String path, String prefix) {
        if (prefix.equals(".")) {
            for (String part : path.split("/")) {
                if (part.startsWith(".")) return true;
            }
            return false;
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    public record Target(boolean allowed, String reason, int computerId, String label, boolean writesEnabled) {
        static Target allowed(int computerId, String label, boolean writesEnabled) {
            return new Target(true, "", computerId, label, writesEnabled);
        }

        static Target denied(String reason) {
            return new Target(false, reason, -1, "", false);
        }
    }
}
