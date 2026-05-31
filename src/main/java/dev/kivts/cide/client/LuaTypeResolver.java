package dev.kivts.cide.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


final class LuaTypeResolver {
    private LuaTypeResolver() {}

    /**
     * Built-in fallback globals — Lua stdlib and the CC modules that don't go through
     * the Java {@code @LuaFunction} registry (Lua-side stdlib lives in Cobalt, not in
     * a registry we can scrape). Dynamic globals discovered from the server manifest
     * are merged in by {@link #isKnownModule(String)}.
     */
    static final Set<String> KNOWN_MODULES = Set.of(
        "turtle", "peripheral", "redstone", "fs", "http", "term",
        "textutils", "colors", "colours", "vector", "gps", "keys",
        "paintutils", "window", "multishell", "pocket", "disk", "shell",
        "commands", "modem", "monitor", "speaker", "drive", "computer",
        "math", "string", "table", "coroutine", "bit32", "io", "os",
        "utf8", "cc"
    );

    private static boolean isKnownModule(String name) {
        return KNOWN_MODULES.contains(name) || LuaCompletionRegistry.dynamicGlobals().contains(name);
    }

    // Public API

    /**
     * Returns a map of varName >>> resolved module/peripheral type for every
     * variable in the file whose type can be statically determined.
     *
     * @param sideToType live map of side/network-name >>> peripheral type from the server
     *                   (pass an empty map when unavailable)
     */
    public static Map<String, String> resolve(LuaAst.Chunk chunk, Map<String, String> sideToType) {
        Map<String, String> out = new LinkedHashMap<>();
        walkBlock(chunk.body(), out, sideToType);
        return out;
    }

    /** Convenience overload with no live side data. */
    public static Map<String, String> resolve(LuaAst.Chunk chunk) {
        return resolve(chunk, Map.of());
    }

    public static Set<String> collectNames(LuaAst.Chunk chunk, int cursorLine) {
        Set<String> out = new LinkedHashSet<>();
        collectNamesBlock(chunk.body(), cursorLine, out);
        return out;
    }

    private static void collectNamesBlock(List<LuaAst.Stat> stats, int cursorLine, Set<String> out) {
        for (LuaAst.Stat stat : stats) {
            if (stat.line() > cursorLine) break; // everything after the cursor is invisible

            switch (stat) {
                case LuaAst.LocalStat ls -> out.addAll(ls.names());

                case LuaAst.LocalFuncStat lf -> {
                    out.add(lf.name()); // visible in outer scope from declaration line
                    // params/body only visible when cursor is inside the function
                    if (cursorLine < lf.endLine()) {
                        out.addAll(lf.params());
                        collectNamesBlock(lf.body(), cursorLine, out);
                    }
                }

                case LuaAst.FuncDefStat fd -> {
                    String top = fd.name().split("[.:]")[0];
                    if (!top.isEmpty()) out.add(top);
                    if (cursorLine < fd.endLine()) {
                        out.addAll(fd.params());
                        collectNamesBlock(fd.body(), cursorLine, out);
                    }
                }

                // Loop variables are scoped to their body
                case LuaAst.ForNumStat fn -> {
                    if (cursorLine < fn.endLine()) {
                        out.add(fn.var());
                        collectNamesBlock(fn.body(), cursorLine, out);
                    }
                }
                case LuaAst.ForInStat fi -> {
                    if (cursorLine < fi.endLine()) {
                        out.addAll(fi.names());
                        collectNamesBlock(fi.body(), cursorLine, out);
                    }
                }

                case LuaAst.DoStat    d  -> { if (cursorLine < d.endLine())  collectNamesBlock(d.body(),  cursorLine, out); }
                case LuaAst.WhileStat w  -> { if (cursorLine < w.endLine())  collectNamesBlock(w.body(),  cursorLine, out); }
                case LuaAst.RepeatStat r -> { if (cursorLine < r.endLine())  collectNamesBlock(r.body(),  cursorLine, out); }

                case LuaAst.IfStat is -> {
                    if (cursorLine >= is.endLine()) break;
                    // Find the exact clause containing the cursor so names don't bleed
                    // across then/elseif/else boundaries.
                    for (int ci = 0; ci < is.clauses().size(); ci++) {
                        LuaAst.IfClause clause = is.clauses().get(ci);
                        int clauseEnd = (ci + 1 < is.clauses().size())
                            ? is.clauses().get(ci + 1).line()
                            : (is.elseBodyLine() >= 0 ? is.elseBodyLine() : is.endLine());
                        if (clause.line() <= cursorLine && cursorLine < clauseEnd) {
                            collectNamesBlock(clause.body(), cursorLine, out);
                            break;
                        }
                    }
                    if (is.elseBodyLine() >= 0 && cursorLine >= is.elseBodyLine())
                        collectNamesBlock(is.elseBody(), cursorLine, out);
                }

                default -> {}
            }
        }
    }


    private static void walkBlock(List<LuaAst.Stat> stats, Map<String, String> out,
                                  Map<String, String> sideToType) {
        for (LuaAst.Stat stat : stats) walkStat(stat, out, sideToType);
    }

    private static void walkStat(LuaAst.Stat stat, Map<String, String> out,
                                  Map<String, String> sideToType) {
        switch (stat) {

            case LuaAst.LocalStat ls -> {
                List<LuaAst.Expr> vals = ls.values();

                // Special-case: single peripheral.find() on RHS fills all LHS names
                if (vals.size() == 1 && isPeripheralFind(vals.get(0))) {
                    String type = typeFromPeripheralFind(vals.get(0), sideToType);
                    if (type != null) {
                        for (String name : ls.names()) out.put(name, type);
                        break;
                    }
                }

                // General: zip names with RHS types
                for (int i = 0; i < ls.names().size(); i++) {
                    String type = i < vals.size() ? exprType(vals.get(i), out, sideToType) : null;
                    if (type != null) out.put(ls.names().get(i), type);
                }
            }

            case LuaAst.AssignStat as -> {
                // peripheral.find() on RHS fills all simple-name targets
                List<LuaAst.Expr> vals = as.values();
                if (vals.size() == 1 && isPeripheralFind(vals.get(0))) {
                    String type = typeFromPeripheralFind(vals.get(0), sideToType);
                    if (type != null) {
                        for (LuaAst.Expr t : as.targets()) {
                            if (t instanceof LuaAst.NameExpr ne) out.put(ne.name(), type);
                        }
                        break;
                    }
                }

                for (int i = 0; i < as.targets().size(); i++) {
                    if (!(as.targets().get(i) instanceof LuaAst.NameExpr ne)) continue;
                    String type = i < vals.size() ? exprType(vals.get(i), out, sideToType) : null;
                    if (type != null) out.put(ne.name(), type);
                }
            }

            // Recurse into all child blocks so inner vars are also captured
            case LuaAst.LocalFuncStat lf -> walkBlock(lf.body(), out, sideToType);
            case LuaAst.FuncDefStat   fd -> walkBlock(fd.body(), out, sideToType);
            case LuaAst.DoStat         d -> walkBlock(d.body(), out, sideToType);
            case LuaAst.WhileStat      w -> walkBlock(w.body(), out, sideToType);
            case LuaAst.RepeatStat     r -> walkBlock(r.body(), out, sideToType);
            case LuaAst.ForNumStat    fn -> walkBlock(fn.body(), out, sideToType);
            case LuaAst.ForInStat     fi -> walkBlock(fi.body(), out, sideToType);
            case LuaAst.IfStat        is -> {
                for (var clause : is.clauses()) walkBlock(clause.body(), out, sideToType);
                walkBlock(is.elseBody(), out, sideToType);
            }

            default -> {} // BreakStat, ReturnStat, CallStat - nothing to infer
        }
    }

    // Expression type resolution

    /** Returns the resolved module/type key for an expression, or null if unknown. */
    static String exprType(LuaAst.Expr expr, Map<String, String> scope,
                           Map<String, String> sideToType) {
        return switch (expr) {

            // Direct name reference - look up in scope or check known globals
            case LuaAst.NameExpr ne -> {
                String t = scope.get(ne.name());
                yield t != null ? t : (isKnownModule(ne.name()) ? ne.name() : null);
            }

            // f(args) calls: peripheral.wrap / peripheral.find / require
            case LuaAst.CallExpr ce -> resolveCall(ce, scope, sideToType);

            // Anything else is not statically resolvable here
            default -> null;
        };
    }

    private static String resolveCall(LuaAst.CallExpr ce, Map<String, String> scope,
                                      Map<String, String> sideToType) {
        // peripheral.wrap("name")  /  peripheral.find("name")
        if (ce.func() instanceof LuaAst.FieldExpr fe
                && fe.object() instanceof LuaAst.NameExpr obj
                && obj.name().equals("peripheral")
                && (fe.field().equals("wrap") || fe.field().equals("find"))
                && !ce.args().isEmpty()
                && ce.args().get(0) instanceof LuaAst.StringExpr arg) {
            return resolvePeripheralName(arg.value(), sideToType);
        }

        // require("module")  /  require "module"
        if (ce.func() instanceof LuaAst.NameExpr fn
                && fn.name().equals("require")
                && !ce.args().isEmpty()
                && ce.args().get(0) instanceof LuaAst.StringExpr arg) {
            return normalise(arg.value());
        }

        return null;
    }


    private static String resolvePeripheralName(String raw, Map<String, String> sideToType) {
        if (raw == null || raw.isBlank()) return null;

        // 1. Live map from server
        String live = sideToType.get(raw);
        if (live != null) return live;

        String stripped = raw.replaceAll("_\\d+$", "");
        if (!stripped.equals(raw) && !stripped.isBlank()) {

            if (isKnownModule(stripped) || stripped.matches("[A-Za-z_][A-Za-z0-9_]*"))
                return stripped;
        }

        // 3. Literal type name (e.g. peripheral.wrap("monitor") on older scripts)
        return normalise(raw);
    }

    // Helpers

    private static boolean isPeripheralFind(LuaAst.Expr e) {
        if (!(e instanceof LuaAst.CallExpr ce)) return false;
        return ce.func() instanceof LuaAst.FieldExpr fe
            && fe.object() instanceof LuaAst.NameExpr obj
            && obj.name().equals("peripheral")
            && fe.field().equals("find")
            && !ce.args().isEmpty();
    }

    private static String typeFromPeripheralFind(LuaAst.Expr e, Map<String, String> sideToType) {
        LuaAst.CallExpr ce = (LuaAst.CallExpr) e;
        if (ce.args().get(0) instanceof LuaAst.StringExpr se)
            return resolvePeripheralName(se.value(), sideToType);
        return null;
    }

    /**
     * Normalises a module/type string coming from source code.
     * "apis/turtle" >>> "turtle",  "advanced_math/pid" >>> "advanced_math.pid",
     * "advanced_math.pid" unchanged, "cc.pretty" unchanged.
     */
    private static String normalise(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("apis/")) {
            String tail = trimmed.substring("apis/".length());
            if (!tail.isBlank() && tail.indexOf('/') < 0) return tail;
        }
        String dotted = trimmed.replace('/', '.');
        return dotted.isBlank() ? null : dotted;
    }
}
