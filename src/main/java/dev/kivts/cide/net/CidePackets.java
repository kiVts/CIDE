package dev.kivts.cide.net;

import dev.kivts.cide.client.CideClient;
import dev.kivts.cide.net.payload.*;
import dev.kivts.cide.server.CideComputerLock;
import dev.kivts.cide.server.CideConsoleService;
import dev.kivts.cide.server.CideFileService;
import dev.kivts.cide.server.CideSessionService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;


public final class CidePackets {
    private CidePackets() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("cide").versioned("12");

        r.playToServer(ListFilesPayload.TYPE,    ListFilesPayload.STREAM_CODEC,    (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.list((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.path())));
        r.playToServer(ReadFilePayload.TYPE,     ReadFilePayload.STREAM_CODEC,     (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.read((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.path())));
        r.playToServer(WriteFilePayload.TYPE,    WriteFilePayload.STREAM_CODEC,    (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.write((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.path(), p.content())));
        r.playToServer(WriteChunkPayload.TYPE,   WriteChunkPayload.STREAM_CODEC,   (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.writeChunk((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.path(), p.chunkIndex(), p.totalChunks(), p.data())));
        r.playToServer(CreateFilePayload.TYPE,   CreateFilePayload.STREAM_CODEC,   (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.create((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.directory())));
        r.playToServer(CreateFolderPayload.TYPE, CreateFolderPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.createFolder((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.path())));
        r.playToServer(DeleteFilePayload.TYPE,   DeleteFilePayload.STREAM_CODEC,   (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.delete((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.path())));
        r.playToServer(CopyFilePayload.TYPE,     CopyFilePayload.STREAM_CODEC,     (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.copy((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.sourcePath(), p.destinationDirectory())));
        r.playToServer(MoveFilePayload.TYPE,     MoveFilePayload.STREAM_CODEC,     (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.move((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.sourcePath(), p.destinationDirectory())));
        r.playToServer(RenameFilePayload.TYPE,   RenameFilePayload.STREAM_CODEC,   (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.rename((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.sourcePath(), p.newName())));
        r.playToServer(PeripheralQueryPayload.TYPE, PeripheralQueryPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideFileService.queryPeripherals((ServerPlayer) ctx.player(), p.pos(), p.computerId())));
        r.playToServer(ToggleLockPayload.TYPE, ToggleLockPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideComputerLock.toggle((ServerPlayer) ctx.player(), p.pos(), p.computerId())));
        r.playToServer(SessionSavePayload.TYPE, SessionSavePayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideSessionService.save((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.json())));
        r.playToServer(SessionSaveChunkPayload.TYPE, SessionSaveChunkPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideSessionService.saveChunk((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.chunkIndex(), p.totalChunks(), p.data())));
        r.playToServer(ConsolePollPayload.TYPE, ConsolePollPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideConsoleService.poll((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.sessionId())));
        r.playToServer(ConsoleActionPayload.TYPE, ConsoleActionPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideConsoleService.action((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.sessionId(), p.action())));
        r.playToServer(ConsoleInputPayload.TYPE, ConsoleInputPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideConsoleService.input((ServerPlayer) ctx.player(), p)));
        r.playToServer(RunProgramPayload.TYPE, RunProgramPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideConsoleService.runProgram((ServerPlayer) ctx.player(), p.pos(), p.computerId(), p.sessionId(), p.path())));
        r.playToServer(DebugSetBreakpointsPayload.TYPE, DebugSetBreakpointsPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> dev.kivts.cide.server.CideDebugService.setBreakpoints(
                (ServerPlayer) ctx.player(), net.minecraft.core.BlockPos.ZERO, p.computerId(), p.breakpoints())));
        r.playToServer(DebugCommandPayload.TYPE, DebugCommandPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> dev.kivts.cide.server.CideDebugService.sendCommand((ServerPlayer) ctx.player(), p.computerId(), p.command())));

        r.playToClient(OpenIdePayload.TYPE,     OpenIdePayload.STREAM_CODEC,     (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.open(p)));
        r.playToClient(FileListPayload.TYPE,    FileListPayload.STREAM_CODEC,    (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleFileList(p)));
        r.playToClient(FileContentPayload.TYPE, FileContentPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleFileContent(p)));
        r.playToClient(FileContentChunkPayload.TYPE, FileContentChunkPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleFileContentChunk(p)));
        r.playToClient(OperationResultPayload.TYPE, OperationResultPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleOperation(p)));
        r.playToClient(PeripheralMapPayload.TYPE, PeripheralMapPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handlePeripheralMap(p)));
        r.playToClient(LockStatePayload.TYPE, LockStatePayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleLockState(p)));
        r.playToClient(SessionLoadPayload.TYPE, SessionLoadPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleSessionLoad(p)));
        r.playToClient(SessionLoadChunkPayload.TYPE, SessionLoadChunkPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleSessionLoadChunk(p)));
        r.playToClient(ConsoleStatePayload.TYPE, ConsoleStatePayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleConsoleState(p)));
        r.playToClient(WikiSyncChunkPayload.TYPE, WikiSyncChunkPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleWikiSyncChunk(p)));
        r.playToClient(LuaManifestPayload.TYPE, LuaManifestPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleLuaManifest(p)));
        r.playToClient(DebugPausedPayload.TYPE, DebugPausedPayload.STREAM_CODEC, (p, ctx) ->
            ctx.enqueueWork(() -> CideClient.handleDebugPaused(p)));
    }

    public static void sendDenied(ServerPlayer player, String reason) {
        PacketDistributor.sendToPlayer(player, new OperationResultPayload(false, reason, ""));
    }
}
