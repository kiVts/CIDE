package dev.kivts.cide.server;

import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.core.ServerContext;
import dev.kivts.cide.net.payload.DebugPausedPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CideDebugService {
    private CideDebugService() {}


    private record DebugSession(UUID playerId, BlockPos pos, int computerId,
                                Map<String, java.util.Set<Integer>> breakpoints,
                                boolean active) {}

    private static final Map<Integer, DebugSession> SESSIONS = new ConcurrentHashMap<>();

    public static synchronized void setBreakpoints(ServerPlayer player, BlockPos pos, int computerId,
                                                    Map<String, List<Integer>> data) {
        Map<String, java.util.Set<Integer>> normalized = new LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, List<Integer>> entry : data.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                java.util.Set<Integer> lines = new java.util.LinkedHashSet<>(entry.getValue());
                lines.remove(null);
                if (!lines.isEmpty()) normalized.put(entry.getKey(), lines);
            }
        }
        DebugSession existing = SESSIONS.get(computerId);
        DebugSession session = new DebugSession(
            player.getUUID(), pos.immutable(), computerId, normalized,
            existing != null && existing.active);
        SESSIONS.put(computerId, session);
        notifyAgentBreakpointsChanged(computerId);
    }

    public static void markActive(int computerId, ServerPlayer player, BlockPos pos) {
        DebugSession existing = SESSIONS.get(computerId);
        Map<String, java.util.Set<Integer>> bps = existing != null ? existing.breakpoints : Map.of();
        SESSIONS.put(computerId, new DebugSession(player.getUUID(), pos.immutable(), computerId, bps, true));
    }

    public static boolean isActive(int computerId) {
        DebugSession s = SESSIONS.get(computerId);
        return s != null && s.active;
    }

    public static Map<String, Map<Integer, Boolean>> getBreakpointsAsLuaTable(int computerId) {
        DebugSession s = SESSIONS.get(computerId);
        if (s == null) return Map.of();
        Map<String, Map<Integer, Boolean>> out = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.Set<Integer>> entry : s.breakpoints.entrySet()) {
            Map<Integer, Boolean> lines = new LinkedHashMap<>();
            for (Integer line : entry.getValue()) lines.put(line, Boolean.TRUE);
            out.put(entry.getKey(), lines);
        }
        return out;
    }

    public static void notifyFinished(int computerId) {
        DebugSession s = SESSIONS.get(computerId);
        if (s == null) return;
        ServerPlayer player = findPlayer(s.playerId);
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new DebugPausedPayload(computerId, "", -1, Map.of()));
        }
        SESSIONS.put(computerId, new DebugSession(s.playerId, s.pos, s.computerId, s.breakpoints, false));
    }

    public static void notifyPaused(int computerId, String file, int line, Map<?, ?> locals) {
        DebugSession s = SESSIONS.get(computerId);
        if (s == null) return;
        ServerPlayer player = findPlayer(s.playerId);
        if (player == null) return;
        Map<String, String> stringLocals = new LinkedHashMap<>();
        if (locals != null) {
            for (Map.Entry<?, ?> e : locals.entrySet()) {
                if (e.getKey() == null) continue;
                stringLocals.put(String.valueOf(e.getKey()), e.getValue() == null ? "nil" : String.valueOf(e.getValue()));
            }
        }
        PacketDistributor.sendToPlayer(player, new DebugPausedPayload(computerId, file, line, stringLocals));
    }

    public static void sendCommand(ServerPlayer player, int computerId, int command) {
        DebugSession s = SESSIONS.get(computerId);
        if (s == null || !s.playerId.equals(player.getUUID())) return;
        ServerComputer computer = findComputer(s.pos, computerId);
        if (computer == null) return;
        String cmd = command == 1 ? "step" : "continue";
        computer.queueEvent("cide_command", new Object[]{ cmd });
    }

    private static void notifyAgentBreakpointsChanged(int computerId) {
        DebugSession s = SESSIONS.get(computerId);
        if (s == null) return;
        ServerComputer computer = findComputer(s.pos, computerId);
        if (computer == null) return;
        computer.queueEvent("cide_breakpoints_changed", new Object[]{});
    }

    public static void clearSession(int computerId, UUID playerId) {
        DebugSession s = SESSIONS.get(computerId);
        if (s != null && s.playerId.equals(playerId)) SESSIONS.remove(computerId);
    }

    public static boolean shouldDebug(int computerId) {
        DebugSession s = SESSIONS.get(computerId);
        if (s == null || s.breakpoints == null) return false;
        for (java.util.Set<Integer> lines : s.breakpoints.values()) {
            if (lines != null && !lines.isEmpty()) return true;
        }
        return false;
    }

    public static String buildRunCommand(String userPath) {
        if (userPath == null || userPath.isBlank()) return null;
        String escaped = userPath.replace("\\", "\\\\").replace("\"", "\\\"");
        return "/rom/cide/run.lua \"" + escaped + "\"";
    }

    private static ServerPlayer findPlayer(UUID playerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(playerId);
    }

    private static ServerComputer findComputer(BlockPos pos, int computerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        if (pos != null && !pos.equals(BlockPos.ZERO)) {
            for (var level : server.getAllLevels()) {
                if (!level.isLoaded(pos)) continue;
                if (level.getBlockEntity(pos) instanceof AbstractComputerBlockEntity be) {
                    ServerComputer sc = be.createServerComputer();
                    if (sc.getID() == computerId) return sc;
                }
            }
        }
        for (ServerComputer sc : ServerContext.get(server).registry().getComputers()) {
            if (sc.getID() == computerId) return sc;
        }
        return null;
    }
}
