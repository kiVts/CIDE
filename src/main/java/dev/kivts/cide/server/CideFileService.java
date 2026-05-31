package dev.kivts.cide.server;

import dev.kivts.cide.config.CideServerConfig;
import dev.kivts.cide.net.CidePackets;
import dev.kivts.cide.net.payload.FileContentChunkPayload;
import dev.kivts.cide.net.payload.FileListPayload;
import dev.kivts.cide.net.payload.OperationResultPayload;
import dev.kivts.cide.net.payload.PeripheralMapPayload;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.api.filesystem.FileOperationException;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.filesystem.MountConstants;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.filesystem.FileSystem;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CideFileService {
    private CideFileService() {}

    private static final int ADMIN_COMPUTER_ID = -1;
    private static final Map<UUID, PendingUpload> PENDING = new ConcurrentHashMap<>();
    private static final int FILE_CONTENT_CHUNK_SIZE = 32 * 1024;
    // Hard cap on totalChunks accepted at chunk 0 — prevents a hostile client from making the
    // server allocate an unbounded byte[][] before any real bytes arrive. 256 chunks = 8 MB.
    private static final int MAX_UPLOAD_CHUNKS = 256;

    // A tombstoned upload (mount == null) makes us silently drop any remaining chunks for an
    // already-denied upload so the client doesn't get a cascade of "out of sequence" messages
    // masking the real denial.
    private record PendingUpload(BlockPos pos, int computerId, String path, byte[][] chunks, WritableMount mount) {
        boolean isTombstone() { return mount == null; }
    }

    public static void writeChunk(ServerPlayer player, BlockPos pos, int computerId,
                                  String path, int chunkIndex, int totalChunks, byte[] data) {
        UUID uuid = player.getUUID();

        if (chunkIndex == 0 && !CideRateLimiter.allow(player)) {
            return;
        }

        if (chunkIndex == 0) {
            PENDING.remove(uuid);
            if (totalChunks < 1 || totalChunks > MAX_UPLOAD_CHUNKS) {
                CidePackets.sendDenied(player, "invalid chunk count"); return;
            }
            String denied = preflightMessage(player, pos, computerId);
            if (denied.isEmpty()) denied = validateWritePath(computerId, path);
            if (!denied.isEmpty()) {
                // Tombstone so chunks 1..N-1 already in flight don't trigger "out of sequence".
                PENDING.put(uuid, new PendingUpload(pos, computerId, path, new byte[totalChunks][], null));
                CidePackets.sendDenied(player, denied); return;
            }
            WritableMount mount;
            try {
                mount = mount(player, pos, computerId);
            } catch (Exception e) {
                PENDING.put(uuid, new PendingUpload(pos, computerId, path, new byte[totalChunks][], null));
                CidePackets.sendDenied(player, message(e)); return;
            }
            PENDING.put(uuid, new PendingUpload(pos, computerId, path, new byte[totalChunks][], mount));
        }

        PendingUpload upload = PENDING.get(uuid);
        if (upload == null || !upload.path().equals(path) || upload.chunks().length != totalChunks
                || chunkIndex < 0 || chunkIndex >= totalChunks) {
            PENDING.remove(uuid);
            CidePackets.sendDenied(player, "upload out of sequence - please retry");
            return;
        }
        if (upload.isTombstone()) {
            // Chunk 0 was already denied; silently consume remaining chunks so the user sees
            // only the original denial. Clear when the last chunk arrives.
            if (chunkIndex == totalChunks - 1) PENDING.remove(uuid);
            return;
        }

        upload.chunks()[chunkIndex] = data;

        if (chunkIndex < totalChunks - 1) return;

        PENDING.remove(uuid);
        long totalBytes = 0;
        for (int i = 0; i < upload.chunks().length; i++) {
            if (upload.chunks()[i] == null) { CidePackets.sendDenied(player, "missing chunk " + i); return; }
            totalBytes += upload.chunks()[i].length;
            if (totalBytes > Integer.MAX_VALUE) {
                CidePackets.sendDenied(player, "file is too large to save"); return;
            }
        }
        WritableMount mount = upload.mount();
        try {
            if (totalBytes > writableBytes(mount, CidePaths.sanitize(upload.path()))) {
                CidePackets.sendDenied(player, "not enough disk space"); return;
            }
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e)); return;
        }
        ByteBuffer buf = ByteBuffer.allocate((int) totalBytes);
        for (byte[] chunk : upload.chunks()) buf.put(chunk);
        buf.flip();
        byte[] bytes = new byte[(int) totalBytes];
        buf.get(bytes);
        writeBytes(player, upload.path(), bytes, mount);
    }

    private static void writeBytes(ServerPlayer player, String rawPath, byte[] bytes, WritableMount mount) {
        String path = CidePaths.sanitize(rawPath);
        try {
            if (mount.exists(path) && mount.isDirectory(path)) {
                CidePackets.sendDenied(player, "cannot overwrite a directory"); return;
            }
            if (bytes.length > writableBytes(mount, path)) {
                CidePackets.sendDenied(player, "not enough disk space"); return;
            }
            mkdirs(mount, FileSystem.getDirectory(path));
            try (var channel = mount.openFile(path, MountConstants.WRITE_OPTIONS)) {
                channel.write(ByteBuffer.wrap(bytes));
            }
            PacketDistributor.sendToPlayer(player, new OperationResultPayload(true, "saved", path));
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e));
        }
    }

    public static void evictUpload(UUID uuid) {
        PENDING.remove(uuid);
    }

    public static void list(ServerPlayer player, BlockPos pos, int computerId, String rawPath) {
        if (!preflight(player, pos, computerId)) return;
        String path = CidePaths.sanitize(rawPath);
        try {
            WritableMount mount = mount(player, pos, computerId);
            List<String> names = new ArrayList<>();
            mount.list(path, names);
            if (isAdmin(computerId) && path.isEmpty()) {
                names.removeIf(name -> !isComputerIdDirectory(mount, name));
            } else {
                names.removeIf(name -> path.isEmpty() && CideComputerLock.isLockPath(name));
            }
            names.sort(Comparator.naturalOrder());

            int limit = Math.min(names.size(), CideServerConfig.MAX_LISTED_ENTRIES.get());
            List<FileListPayload.Entry> entries = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                String child = path.isEmpty() ? names.get(i) : path + "/" + names.get(i);
                entries.add(new FileListPayload.Entry(
                    child,
                    mount.isDirectory(child),
                    mount.exists(child) ? mount.getSize(child) : 0,
                    mount.isReadOnly(child)
                ));
            }

            long freeSpace = -1;
            try { freeSpace = mount.getRemainingSpace(); } catch (Exception ignored) {}

            PacketDistributor.sendToPlayer(player,
                new FileListPayload(computerId, path, entries, names.size() > limit, freeSpace));
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e));
        }
    }

    public static void read(ServerPlayer player, BlockPos pos, int computerId, String rawPath) {
        if (!preflight(player, pos, computerId)) return;
        String denied = validateReadPath(computerId, rawPath);
        if (!denied.isEmpty()) { CidePackets.sendDenied(player, denied); return; }

        String path = CidePaths.sanitize(rawPath);
        try {
            WritableMount mount = mount(player, pos, computerId);
            if (!mount.exists(path)) {
                PacketDistributor.sendToPlayer(player, new OperationResultPayload(false, "file does not exist", path));
                return;
            }
            if (mount.isDirectory(path)) { CidePackets.sendDenied(player, "cannot read a directory"); return; }
            long size = mount.getSize(path);
            if (size > Integer.MAX_VALUE) { CidePackets.sendDenied(player, "file is too large to open"); return; }
            sendFileContent(player, computerId, path, readBytes(mount, path, (int) size), mount.isReadOnly(path));
        } catch (Exception e) {
            String msg = message(e);
            if (isMissingFileMessage(msg)) {
                PacketDistributor.sendToPlayer(player, new OperationResultPayload(false, "file does not exist", path));
            } else {
                CidePackets.sendDenied(player, msg);
            }
        }
    }

    public static void write(ServerPlayer player, BlockPos pos, int computerId, String rawPath, String content) {
        if (!preflight(player, pos, computerId)) return;
        String denied = validateWritePath(computerId, rawPath);
        if (!denied.isEmpty()) { CidePackets.sendDenied(player, denied); return; }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String path = CidePaths.sanitize(rawPath);
        try {
            WritableMount mount = mount(player, pos, computerId);
            if (mount.exists(path) && mount.isDirectory(path)) {
                CidePackets.sendDenied(player, "cannot overwrite a directory"); return;
            }
            if (bytes.length > writableBytes(mount, path)) {
                CidePackets.sendDenied(player, "not enough disk space"); return;
            }
            mkdirs(mount, FileSystem.getDirectory(path));
            try (var channel = mount.openFile(path, MountConstants.WRITE_OPTIONS)) {
                channel.write(ByteBuffer.wrap(bytes));
            }
            PacketDistributor.sendToPlayer(player, new OperationResultPayload(true, "saved", path));
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e));
        }
    }

    public static void create(ServerPlayer player, BlockPos pos, int computerId, String rawDirectory) {
        if (!preflight(player, pos, computerId)) return;
        String directory = CidePaths.sanitize(rawDirectory);
        String dirCheck = validateWritePath(computerId, directory.isEmpty() ? "new.lua" : directory + "/new.lua");
        if (!dirCheck.isEmpty()) { CidePackets.sendDenied(player, dirCheck); return; }
        try {
            WritableMount mount = mount(player, pos, computerId);
            String path = uniquePath(mount, directory, "new", ".lua");
            try (var channel = mount.openFile(path, MountConstants.WRITE_OPTIONS)) {
                channel.write(ByteBuffer.wrap(new byte[0]));
            }
            PacketDistributor.sendToPlayer(player, new OperationResultPayload(true, "created", path));
            sendFileContent(player, computerId, path, new byte[0], false);
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e));
        }
    }

    public static void createFolder(ServerPlayer player, BlockPos pos, int computerId, String rawPath) {
        if (!preflight(player, pos, computerId)) return;
        String denied = validateWritePath(computerId, rawPath);
        if (!denied.isEmpty()) { CidePackets.sendDenied(player, denied); return; }
        String path = CidePaths.sanitize(rawPath);
        try {
            WritableMount mount = mount(player, pos, computerId);
            if (mount.exists(path)) { CidePackets.sendDenied(player, "already exists"); return; }
            mount.makeDirectory(path);
            PacketDistributor.sendToPlayer(player, new OperationResultPayload(true, "created folder", path));
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e));
        }
    }

    public static void delete(ServerPlayer player, BlockPos pos, int computerId, String rawPath) {
        if (!preflight(player, pos, computerId)) return;
        String denied = validateWritePath(computerId, rawPath);
        if (!denied.isEmpty()) { CidePackets.sendDenied(player, denied); return; }
        String path = CidePaths.sanitize(rawPath);
        try {
            WritableMount mount = mount(player, pos, computerId);
            if (!mount.exists(path)) { CidePackets.sendDenied(player, "file does not exist"); return; }
            mount.delete(path);
            PacketDistributor.sendToPlayer(player, new OperationResultPayload(true, "deleted", path));
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e));
        }
    }

    public static void copy(ServerPlayer player, BlockPos pos, int computerId, String rawSource, String rawDestDir) {
        if (!preflight(player, pos, computerId)) return;
        transfer(player, pos, computerId, rawSource, rawDestDir, false);
    }

    public static void move(ServerPlayer player, BlockPos pos, int computerId, String rawSource, String rawDestDir) {
        if (!preflight(player, pos, computerId)) return;
        transfer(player, pos, computerId, rawSource, rawDestDir, true);
    }

    public static void rename(ServerPlayer player, BlockPos pos, int computerId, String rawSource, String rawNewName) {
        if (!preflight(player, pos, computerId)) return;
        String source   = CidePaths.sanitize(rawSource);
        String newName  = CidePaths.sanitize(rawNewName).replace("/", "");
        if (newName.isBlank()) { CidePackets.sendDenied(player, "invalid name"); return; }
        String directory = FileSystem.getDirectory(source);
        String destination = directory.isEmpty() ? newName : directory + "/" + newName;
        String denied = validateWritePath(computerId, source);
        if (denied.isEmpty()) denied = validateWritePath(computerId, destination);
        if (!denied.isEmpty()) { CidePackets.sendDenied(player, denied); return; }
        try {
            WritableMount mount = mount(player, pos, computerId);
            if (!mount.exists(source)) { CidePackets.sendDenied(player, "file does not exist"); return; }
            if (mount.isDirectory(source)) { CidePackets.sendDenied(player, "directory rename not supported yet"); return; }
            if (mount.exists(destination)) { CidePackets.sendDenied(player, "name already taken"); return; }
            copyFile(mount, source, destination);
            mount.delete(source);
            PacketDistributor.sendToPlayer(player, new OperationResultPayload(true, "renamed", destination));
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e));
        }
    }

    private static void transfer(ServerPlayer player, BlockPos pos, int computerId, String rawSource, String rawDestDir, boolean move) {
        String source = CidePaths.sanitize(rawSource);
        String destDir = CidePaths.sanitize(rawDestDir);
        String denied = move ? validateWritePath(computerId, source) : validateReadPath(computerId, source);
        if (denied.isEmpty()) denied = validateWritePath(computerId, destDir + "/x");
        if (!denied.isEmpty()) { CidePackets.sendDenied(player, denied); return; }

        try {
            WritableMount mount = mount(player, pos, computerId);
            if (!mount.exists(source)) { CidePackets.sendDenied(player, "source does not exist"); return; }
            if (mount.isDirectory(source)) { CidePackets.sendDenied(player, "directory transfers not supported yet"); return; }
            if (!destDir.isEmpty() && (!mount.exists(destDir) || !mount.isDirectory(destDir))) {
                CidePackets.sendDenied(player, "destination is not a directory"); return;
            }
            String destination = uniquePath(mount, destDir, FileSystem.getName(source), "");
            copyFile(mount, source, destination);
            if (move) mount.delete(source);
            PacketDistributor.sendToPlayer(player,
                new OperationResultPayload(true, move ? "moved" : "copied", destination));
        } catch (Exception e) {
            CidePackets.sendDenied(player, message(e));
        }
    }

    private static void copyFile(WritableMount mount, String source, String destination) throws IOException {
        long size = mount.getSize(source);
        if (size > Integer.MAX_VALUE) throw new IOException("file is too large to copy");
        if (size > writableBytes(mount, destination)) throw new IOException("not enough disk space");
        try (var output = mount.openFile(destination, MountConstants.WRITE_OPTIONS)) {
            output.write(ByteBuffer.wrap(readBytes(mount, source, (int) size)));
        }
    }

    private static byte[] readBytes(WritableMount mount, String path, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        try (var channel = mount.openForRead(path)) {
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
        }
        return buffer.array();
    }

    private static void sendFileContent(ServerPlayer player, int computerId, String path, byte[] bytes, boolean readOnly) {
        int totalChunks = Math.max(1, (bytes.length + FILE_CONTENT_CHUNK_SIZE - 1) / FILE_CONTENT_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * FILE_CONTENT_CHUNK_SIZE;
            byte[] chunk = Arrays.copyOfRange(bytes, start, Math.min(start + FILE_CONTENT_CHUNK_SIZE, bytes.length));
            PacketDistributor.sendToPlayer(player,
                new FileContentChunkPayload(computerId, path, i, totalChunks, chunk, readOnly));
        }
    }

    private static String uniquePath(WritableMount mount, String directory, String baseName, String extension)
            throws IOException {
        String cleanBase = CidePaths.sanitize(baseName).replace("/", "");
        String cleanExtension = extension == null ? "" : extension;
        if (cleanExtension.isEmpty()) {
            int dot = cleanBase.lastIndexOf('.');
            if (dot > 0 && dot < cleanBase.length() - 1) {
                cleanExtension = cleanBase.substring(dot);
                cleanBase = cleanBase.substring(0, dot);
            }
        }
        if (cleanBase.isBlank()) cleanBase = "new";
        for (int i = 0; i < 1000; i++) {
            String name = i == 0 ? cleanBase + cleanExtension : cleanBase + "_" + i + cleanExtension;
            String path = directory.isEmpty() ? name : directory + "/" + name;
            if (!mount.exists(path)) return path;
        }
        throw new IOException("could not find a free filename");
    }

    public static void queryPeripherals(ServerPlayer player, BlockPos pos, int computerId) {
        String denied = CideAccess.validateLiveTarget(player, pos, computerId);
        if (!denied.isEmpty()) return; // silently ignore - this is best-effort for autocomplete

        Map<String, String> sideToType = new LinkedHashMap<>();
        Map<String, IPeripheral> sideToPeripheral = new LinkedHashMap<>();

        // Pocket computers have no block and therefore no sides
        if (!pos.equals(BlockPos.ZERO)) {
            BlockEntity be = player.level().getBlockEntity(pos);
            if (be instanceof AbstractComputerBlockEntity computer) {
                ServerComputer sc = computer.createServerComputer();
                for (ComputerSide side : ComputerSide.values()) {
                    IPeripheral p = sc.getPeripheral(side);
                    if (p != null) {
                        sideToType.put(side.getName(), p.getType());
                        sideToPeripheral.put(side.getName(), p);
                        collectWiredNetwork(p, sideToType, sideToPeripheral);
                    }
                }
            }
        }

        PacketDistributor.sendToPlayer(player, new PeripheralMapPayload(computerId, sideToType));
        PacketDistributor.sendToPlayer(player, dev.kivts.cide.server.CideLuaCatalog.buildWithAttached(sideToPeripheral));
    }
    //look through wired network for all peripherals, if any. used in autocomplete.
    private static void collectWiredNetwork(IPeripheral attachedPeripheral,
                                            Map<String, String> sideToType,
                                            Map<String, IPeripheral> sideToPeripheral) {
        if (!(attachedPeripheral instanceof dan200.computercraft.shared.peripheral.modem.wired.WiredModemPeripheral modem)) return;
        try {
            var element = modem.getNode().getElement();
            if (!(element instanceof dan200.computercraft.shared.peripheral.modem.wired.WiredModemElement modemElement)) return;
            Map<String, IPeripheral> remote = modemElement.getRemotePeripherals();
            synchronized (remote) {
                for (Map.Entry<String, IPeripheral> entry : remote.entrySet()) {
                    String name = entry.getKey();
                    IPeripheral peripheral = entry.getValue();
                    if (name == null || peripheral == null) continue;
                    sideToType.putIfAbsent(name, peripheral.getType());
                    sideToPeripheral.putIfAbsent(name, peripheral);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean preflight(ServerPlayer player, BlockPos pos, int computerId) {
        if (!CideRateLimiter.allow(player)) {return false; }
        String denied = preflightMessage(player, pos, computerId);
        if (!denied.isEmpty()) { CidePackets.sendDenied(player, denied); return false; }
        return true;
    }

    private static String preflightMessage(ServerPlayer player, BlockPos pos, int computerId) {
        if (isAdmin(computerId)) return player.hasPermissions(2) ? "" : "operator permissions required";
        return CideAccess.validateLiveTarget(player, pos, computerId);
    }

    private static WritableMount mount(ServerPlayer player, BlockPos pos, int computerId) {
        if (isAdmin(computerId)) {
            return ComputerCraftAPI.createSaveDirMount(player.server, "computer", Long.MAX_VALUE / 4);
        }
        ServerComputer computer = resolveComputer(player, pos, computerId);
        if (computer == null) throw new IllegalStateException("computer no longer exists");
        WritableMount live = liveRootMount(computer);
        return live != null ? live : computer.createRootMount();
    }

    // Cached reflection handles for grabbing the running computer's WritableMount instance.
    // Writing through the live mount keeps its cached usedSpace in sync, so the in-game
    // `fs.getFreeSpace` / `drive` numbers stay accurate after CIDE edits. If anything in
    // the chain changes (CC update, computer not booted), we fall back to a fresh mount.
    private static volatile Field SERVER_COMPUTER_COMPUTER_FIELD;
    private static volatile Field COMPUTER_EXECUTOR_FIELD;
    private static volatile Field EXECUTOR_ROOT_MOUNT_FIELD;
    private static volatile boolean reflectionBroken = false;

    private static WritableMount liveRootMount(ServerComputer serverComputer) {
        if (reflectionBroken) return null;
        try {
            if (SERVER_COMPUTER_COMPUTER_FIELD == null) {
                Field f = serverComputer.getClass().getDeclaredField("computer");
                f.setAccessible(true);
                SERVER_COMPUTER_COMPUTER_FIELD = f;
            }
            Object computer = SERVER_COMPUTER_COMPUTER_FIELD.get(serverComputer);
            if (computer == null) return null;
            if (COMPUTER_EXECUTOR_FIELD == null) {
                Field f = computer.getClass().getDeclaredField("executor");
                f.setAccessible(true);
                COMPUTER_EXECUTOR_FIELD = f;
            }
            Object executor = COMPUTER_EXECUTOR_FIELD.get(computer);
            if (executor == null) return null;
            if (EXECUTOR_ROOT_MOUNT_FIELD == null) {
                Field f = executor.getClass().getDeclaredField("rootMount");
                f.setAccessible(true);
                EXECUTOR_ROOT_MOUNT_FIELD = f;
            }
            Object mount = EXECUTOR_ROOT_MOUNT_FIELD.get(executor);
            return mount instanceof WritableMount wm ? wm : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            reflectionBroken = true;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ServerComputer resolveComputer(ServerPlayer player, BlockPos pos, int computerId) {
        if (!pos.equals(BlockPos.ZERO)) {
            BlockEntity blockEntity = player.level().getBlockEntity(pos);
            if (blockEntity instanceof AbstractComputerBlockEntity computer) {
                ServerComputer serverComputer = computer.createServerComputer();
                return serverComputer.getID() == computerId ? serverComputer : null;
            }
            return null;
        }
        for (ServerComputer computer : ServerContext.get(player.server).registry().getComputers()) {
            if (computer.getID() == computerId) return computer;
        }
        return null;
    }

    private static long writableBytes(WritableMount mount, String path) throws IOException {
        long available = mount.getRemainingSpace();
        if (!path.isEmpty() && mount.exists(path) && !mount.isDirectory(path)) {
            available += mount.getSize(path);
        }
        return Math.max(0, available);
    }

    private static boolean isAdmin(int computerId) {
        return computerId == ADMIN_COMPUTER_ID;
    }

    private static boolean isComputerIdDirectory(WritableMount mount, String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        try {
            return mount.exists(name) && mount.isDirectory(name);
        } catch (IOException e) {
            return false;
        }
    }

    private static String validateReadPath(int computerId, String rawPath) {
        if (!isAdmin(computerId)) return CideAccess.validatePath(rawPath, false);
        String inner = adminInnerPath(rawPath);
        if (inner == null) return "Choose a computer folder first.";
        return CideAccess.validatePath(inner, false);
    }

    private static String validateWritePath(int computerId, String rawPath) {
        if (!isAdmin(computerId)) return CideAccess.validatePath(rawPath, true);
        String inner = adminInnerPath(rawPath);
        if (inner == null) return "Choose a computer folder first.";
        return CideAccess.validatePath(inner, true);
    }

    private static String adminInnerPath(String rawPath) {
        String path = CidePaths.sanitize(rawPath);
        int slash = path.indexOf('/');
        if (slash <= 0) return null;
        String id = path.substring(0, slash);
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) return null;
        }
        String inner = path.substring(slash + 1);
        return inner.isEmpty() ? null : inner;
    }

    private static void mkdirs(WritableMount mount, String path) throws IOException {
        if (path.isEmpty() || path.equals("..") || mount.exists(path)) return;
        mkdirs(mount, FileSystem.getDirectory(path));
        mount.makeDirectory(path);
    }

    private static String message(Exception e) {
        if (e instanceof FileOperationException || e instanceof IOException) return e.getMessage();
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static boolean isMissingFileMessage(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("no such file") || lower.contains("does not exist");
    }
}
