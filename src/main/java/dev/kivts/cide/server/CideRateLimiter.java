package dev.kivts.cide.server;

import dev.kivts.cide.config.CideServerConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CideRateLimiter {
    private static final Map<UUID, Window> WINDOWS = new ConcurrentHashMap<>();

    private CideRateLimiter() {
    }

    static boolean allow(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Window window = WINDOWS.computeIfAbsent(player.getUUID(), ignored -> new Window(now));
        if (now - window.startedAt >= 1000) {
            window.startedAt = now;
            window.requests = 0;
        }
        window.requests++;
        return window.requests <= CideServerConfig.RATE_LIMIT_RPS.get();
    }

    public static void evict(UUID uuid) {
        WINDOWS.remove(uuid);
    }

    private static final class Window {
        long startedAt;
        int requests;

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
