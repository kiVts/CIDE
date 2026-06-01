package dev.kivts.cide.server;

import dan200.computercraft.core.input.UserComputerInput;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.core.ServerContext;
import dev.kivts.cide.net.CidePackets;
import dev.kivts.cide.net.payload.ConsoleActionPayload;
import dev.kivts.cide.net.payload.ConsoleInputPayload;
import dev.kivts.cide.net.payload.ConsoleStatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CideConsoleService {
    private CideConsoleService() {}

    private static final int MAX_PASTE_BYTES = 8192;
    private static final int MAX_TERMINAL_BYTES = 64 * 1024;
    private static final int ENTER_KEY = 257;
    private static final long SESSION_TTL_MS = 10 * 60 * 1000L;
    private static final Map<UUID, ConsoleSession> SESSIONS = new ConcurrentHashMap<>();

    private record ConsoleSession(UUID playerId, BlockPos pos, int computerId, long createdAt, long touchedAt,
                                  UUID computerInstance, UserComputerInput input) {
        ConsoleSession touch(UserComputerInput newInput, UUID newInstance) {
            return new ConsoleSession(playerId, pos, computerId, createdAt, System.currentTimeMillis(),
                newInstance, newInput);
        }
    }

    public static UUID openSession(ServerPlayer player, BlockPos pos, int computerId) {
        evictExpired();
        UUID id = UUID.randomUUID();
        SESSIONS.put(id, new ConsoleSession(player.getUUID(), pos.immutable(), computerId,
            System.currentTimeMillis(), System.currentTimeMillis(), null, null));
        return id;
    }

    public static void close(UUID playerId) {
        for (Iterator<Map.Entry<UUID, ConsoleSession>> it = SESSIONS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, ConsoleSession> entry = it.next();
            if (entry.getValue().playerId.equals(playerId)) {
                UserComputerInput input = entry.getValue().input;
                if (input != null) input.releaseInputs();
                it.remove();
            }
        }
    }

    public static void poll(ServerPlayer player, BlockPos pos, int computerId, UUID sessionId) {
        if (!CideRateLimiter.allow(player)) return;
        ServerComputer computer = resolve(player, pos, computerId, sessionId);
        if (computer == null) return;
        touch(sessionId, computer);
        sendState(player, computerId, computer);
    }

    public static void action(ServerPlayer player, BlockPos pos, int computerId, UUID sessionId, int action) {
        if (action == ConsoleActionPayload.CLOSE) {
            remove(sessionId, player.getUUID());
            return;
        }
        if (action == ConsoleActionPayload.RELEASE_INPUTS) {
            release(sessionId, player.getUUID());
            return;
        }
        if (!CideRateLimiter.allow(player)) return;

        ServerComputer computer = resolve(player, pos, computerId, sessionId);
        if (computer == null) return;
        switch (action) {
            case ConsoleActionPayload.TERMINATE -> {
                computer.queueEvent("terminate");
                CideDebugService.notifyFinished(computerId);
            }
            case ConsoleActionPayload.TURN_ON -> computer.turnOn();
            case ConsoleActionPayload.SHUTDOWN -> {
                computer.shutdown();
                CideDebugService.notifyFinished(computerId);
            }
            case ConsoleActionPayload.REBOOT -> {
                computer.reboot();
                CideDebugService.notifyFinished(computerId);
            }
            default -> { return; }
        }
        touch(sessionId, computer);
        sendState(player, computerId, computer);
    }

    public static void input(ServerPlayer player, ConsoleInputPayload payload) {
        boolean releaseInput = payload.action() == ConsoleInputPayload.KEY_UP
            || payload.action() == ConsoleInputPayload.MOUSE_UP;
        if (!releaseInput && !CideRateLimiter.allow(player)) return;
        ServerComputer computer = resolve(player, payload.pos(), payload.computerId(), payload.sessionId());
        if (computer == null) return;

        UserComputerInput input = inputFor(payload.sessionId(), computer);
        if (input == null) return;

        switch (payload.action()) {
            case ConsoleInputPayload.KEY_DOWN -> input.keyDown(payload.a(), payload.b() != 0);
            case ConsoleInputPayload.KEY_UP -> input.keyUp(payload.a());
            case ConsoleInputPayload.CHAR -> input.codepointTyped(payload.a());
            case ConsoleInputPayload.PASTE -> {
                byte[] bytes = payload.text().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_PASTE_BYTES) {
                    CidePackets.sendDenied(player, "console paste is too large");
                    return;
                }
                input.paste(payload.text());
            }
            case ConsoleInputPayload.MOUSE_CLICK -> input.mouseClick(payload.a(), payload.x(), payload.y());
            case ConsoleInputPayload.MOUSE_UP -> input.mouseUp(payload.a(), payload.x(), payload.y());
            case ConsoleInputPayload.MOUSE_DRAG -> input.mouseDrag(payload.a(), payload.x(), payload.y());
            case ConsoleInputPayload.MOUSE_SCROLL -> input.mouseScroll(payload.a(), payload.x(), payload.y());
            default -> { return; }
        }
        sendState(player, payload.computerId(), computer);
    }

    public static void runProgram(ServerPlayer player, BlockPos pos, int computerId, UUID sessionId, String path) {
        if (!CideRateLimiter.allow(player)) return;
        path = cleanRunPath(path);
        if (path == null) {
            CidePackets.sendDenied(player, "invalid program path");
            return;
        }

        ServerComputer computer = resolve(player, pos, computerId, sessionId);
        if (computer == null) return;
        computer.turnOn();

        UserComputerInput input = inputFor(sessionId, computer);
        if (input == null) return;

        boolean debug = CideDebugService.shouldDebug(computerId);
        String command;
        if (debug) {
            command = CideDebugService.buildRunCommand(path);
            if (command == null) command = shellCommand(path);
            else CideDebugService.markActive(computerId, player, pos);
        } else {
            command = shellCommand(path);
        }

        input.paste("clear");
        pressEnter(input);
        input.paste(command);
        pressEnter(input);
        sendState(player, computerId, computer);
    }

    private static void pressEnter(UserComputerInput input) {
        input.keyDown(ENTER_KEY, false);
        input.keyUp(ENTER_KEY);
    }

    private static void sendState(ServerPlayer player, int computerId, ServerComputer computer) {
        var state = computer.getTerminalState();
        if (state != null && state.size() > MAX_TERMINAL_BYTES) state = null;
        PacketDistributor.sendToPlayer(player, new ConsoleStatePayload(computerId, computer.isOn(), state));
    }

    private static String cleanRunPath(String path) {
        if (path == null) return null;
        path = path.replace('\\', '/').strip();
        if (path.isEmpty() || path.length() > 256 || path.startsWith("/") || path.contains("..")
                || path.indexOf('\0') >= 0 || path.indexOf('"') >= 0
                || path.startsWith("wiki:") || path.startsWith("console:")) {
            return null;
        }
        return path;
    }

    private static String shellCommand(String path) {
        return path.indexOf(' ') >= 0 || path.indexOf('\t') >= 0 ? "\"" + path + "\"" : path;
    }

    private static UserComputerInput inputFor(UUID sessionId, ServerComputer computer) {
        ConsoleSession session = SESSIONS.get(sessionId);
        if (session == null) return null;
        if (session.input != null && computer.getInstanceUUID().equals(session.computerInstance)) {
            SESSIONS.put(sessionId, session.touch(session.input, session.computerInstance));
            return session.input;
        }
        if (session.input != null) session.input.releaseInputs();
        UserComputerInput input = computer.createComputerInput();
        SESSIONS.put(sessionId, session.touch(input, computer.getInstanceUUID()));
        return input;
    }

    private static void touch(UUID sessionId, ServerComputer computer) {
        inputFor(sessionId, computer);
    }

    private static ServerComputer resolve(ServerPlayer player, BlockPos pos, int computerId, UUID sessionId) {
        evictExpired();
        ConsoleSession session = SESSIONS.get(sessionId);
        if (session == null || !session.playerId.equals(player.getUUID())
                || session.computerId != computerId || !session.pos.equals(pos)) {
            CidePackets.sendDenied(player, "console session expired");
            return null;
        }

        String denied = CideAccess.validateLiveTarget(player, pos, computerId);
        if (!denied.isEmpty()) {
            CidePackets.sendDenied(player, denied);
            return null;
        }

        if (!pos.equals(BlockPos.ZERO)) {
            if (!player.level().isLoaded(pos)) return null;
            BlockEntity be = player.level().getBlockEntity(pos);
            if (be instanceof AbstractComputerBlockEntity computer) {
                ServerComputer serverComputer = computer.createServerComputer();
                return serverComputer.getID() == computerId ? serverComputer : null;
            }
            return null;
        }

        for (ServerComputer computer : ServerContext.get(player.server).registry().getComputers()) {
            if (computer.getID() == computerId) return computer;
        }
        CidePackets.sendDenied(player, "computer is not loaded");
        return null;
    }

    private static void remove(UUID sessionId, UUID playerId) {
        ConsoleSession session = SESSIONS.get(sessionId);
        if (session == null || !session.playerId.equals(playerId)) return;
        if (session.input != null) session.input.releaseInputs();
        SESSIONS.remove(sessionId);
    }

    private static void release(UUID sessionId, UUID playerId) {
        ConsoleSession session = SESSIONS.get(sessionId);
        if (session == null || !session.playerId.equals(playerId)) return;
        if (session.input != null) session.input.releaseInputs();
    }

    private static void evictExpired() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, ConsoleSession>> it = SESSIONS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, ConsoleSession> entry = it.next();
            if (now - entry.getValue().touchedAt > SESSION_TTL_MS) {
                UserComputerInput input = entry.getValue().input;
                if (input != null) input.releaseInputs();
                it.remove();
            }
        }
    }
}
