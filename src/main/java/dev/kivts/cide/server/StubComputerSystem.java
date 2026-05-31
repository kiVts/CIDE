package dev.kivts.cide.server;

import dan200.computercraft.api.component.ComputerComponent;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.WorkMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.concurrent.TimeUnit;

final class StubComputerSystem implements IComputerSystem {
    @Override public ServerLevel getLevel()                                                            { return null; }
    @Override public BlockPos getPosition()                                                            { return BlockPos.ZERO; }
    @Override public String getLabel()                                                                 { return null; }
    @Override public <T> T getComponent(ComputerComponent<T> component)                                { return null; }

    @Override public String mount(String desiredLocation, Mount mount, String driveName)              { return desiredLocation; }
    @Override public String mountWritable(String desiredLocation, WritableMount mount, String driveName) { return desiredLocation; }
    @Override public void unmount(String location)                                                     {}
    @Override public int getID()                                                                       { return -1; }
    @Override public void queueEvent(String event, Object... arguments)                                {}
    @Override public String getAttachmentName()                                                        { return "cide_catalog_stub"; }
    @Override public Map<String, IPeripheral> getAvailablePeripherals()                               { return Map.of(); }
    @Override public IPeripheral getAvailablePeripheral(String name)                                   { return null; }
    @Override public WorkMonitor getMainThreadMonitor()                                                { return STUB_MONITOR; }

    private static final WorkMonitor STUB_MONITOR = new WorkMonitor() {
        @Override public boolean canWork()    { return false; }
        @Override public boolean shouldWork() { return false; }
        @Override public void trackWork(long time, TimeUnit unit) {}
    };
}
