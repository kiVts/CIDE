local args = { ... }
local userProgram = args[1]
if not userProgram or userProgram == "" then
    printError("usage: /rom/cide/run.lua <program>")
    return
end

term.clear()
term.setCursorPos(1, 1)

local ok_load, dbg = pcall(dofile, "/rom/cide/agent.lua")
if not ok_load or type(dbg) ~= "table" then
    printError("cide debugger failed to load: " .. tostring(dbg))
    shell.run(userProgram)
    return
end

dbg.install()
local ok, err = pcall(shell.run, userProgram)
dbg.uninstall()
if not ok and err and err ~= "" then printError(tostring(err)) end
