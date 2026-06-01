local M = {}

local original_create = coroutine.create
local original_wrap = coroutine.wrap

local function normalize_source(src)
    if type(src) ~= "string" or #src == 0 then return "" end
    if src:sub(1, 1) == "@" then src = src:sub(2) end
    if src:sub(1, 1) == "/" then src = src:sub(2) end
    return src
end

local function snapshot_locals(level)
    local out = {}
    local i = 1
    while true do
        local name, value = debug.getlocal(level, i)
        if not name then break end
        if name:sub(1, 1) ~= "(" then
            local kind = type(value)
            local repr
            if kind == "string" then repr = string.format("%q", value)
            elseif kind == "number" or kind == "boolean" or kind == "nil" then repr = tostring(value)
            elseif kind == "table" then repr = "<table>"
            elseif kind == "function" then repr = "<function>"
            else repr = "<" .. kind .. ">" end
            out[name] = repr
        end
        i = i + 1
    end
    return out
end

local function stack_depth(start_level)
    local d = 0
    local lvl = start_level
    while debug.getinfo(lvl, "S") do d = d + 1; lvl = lvl + 1 end
    return d
end

local state = { bps = {}, stepping = false, step_depth = 0, hook_tick = 0 }

local function refresh_bps()
    local ok, result = pcall(cide_dbg.getBreakpoints)
    if ok and result then state.bps = result end
end

local function hook()
    if not cide_dbg or not cide_dbg.isActive() then return end
    local info = debug.getinfo(2, "Sl")
    if not info or not info.currentline or info.currentline < 0 then return end

    state.hook_tick = state.hook_tick + 1
    if state.hook_tick % 32 == 0 then refresh_bps() end

    local source = normalize_source(info.source)
    if source:sub(1, 4) == "rom/" or source == "" then return end

    local should_break = false
    if state.stepping then
        local depth = stack_depth(2)
        if depth <= state.step_depth then
            should_break = true
            state.stepping = false
        end
    elseif state.bps[source] and state.bps[source][info.currentline] then
        should_break = true
    end

    if not should_break then return end

    local locals = snapshot_locals(3)
    pcall(cide_dbg.paused, source, info.currentline, locals)

    while true do
        local ev = { os.pullEventRaw() }
        local name = ev[1]
        if name == "cide_command" then
            local cmd = ev[2]
            if cmd == "step" then
                state.stepping = true
                state.step_depth = stack_depth(2)
            end
            break
        elseif name == "cide_breakpoints_changed" then
            refresh_bps()
        elseif name == "terminate" then
            error("Terminated", 0)
        end
    end
end

function M.install()
    refresh_bps()
    debug.sethook(hook, "l")
    coroutine.create = function(f)
        local co = original_create(f)
        debug.sethook(co, hook, "l")
        return co
    end
    coroutine.wrap = function(f)
        local co = original_create(f)
        debug.sethook(co, hook, "l")
        return function(...)
            local results = { coroutine.resume(co, ...) }
            if results[1] then return table.unpack(results, 2) end
            error(results[2], 2)
        end
    end
end

function M.uninstall()
    debug.sethook()
    coroutine.create = original_create
    coroutine.wrap = original_wrap
    if cide_dbg and cide_dbg.finished then pcall(cide_dbg.finished) end
end

return M
