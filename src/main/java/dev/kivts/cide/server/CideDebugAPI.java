package dev.kivts.cide.server;

import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;

import java.util.Map;

public final class CideDebugAPI implements ILuaAPI {
    private final IComputerSystem system;

    public CideDebugAPI(IComputerSystem system) {
        this.system = system;
    }

    @Override
    public String[] getNames() {
        return new String[]{ "cide_dbg" };
    }

    @LuaFunction
    public final void paused(String file, int line, Map<?, ?> locals) {
        CideDebugService.notifyPaused(system.getID(), file == null ? "" : file, line, locals);
    }

    @LuaFunction
    public final Map<String, Map<Integer, Boolean>> getBreakpoints() {
        return CideDebugService.getBreakpointsAsLuaTable(system.getID());
    }

    @LuaFunction
    public final boolean isActive() {
        return CideDebugService.isActive(system.getID());
    }

    @LuaFunction
    public final void finished() {
        CideDebugService.notifyFinished(system.getID());
    }
}
