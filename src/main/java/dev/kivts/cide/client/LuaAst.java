package dev.kivts.cide.client;

import java.util.List;

/** Lua 5.2 AST nodes used by LuaParser and LuaTypeResolver. */
final class LuaAst {
    private LuaAst() {}

    // Statements - every Stat carries the source line it starts on (0-based)

    sealed interface Stat permits
        LocalStat, AssignStat, FuncDefStat, LocalFuncStat,
        CallStat, DoStat, WhileStat, RepeatStat,
        IfStat, ForNumStat, ForInStat, ReturnStat, BreakStat {
        int line();
    }

    /** local n1, n2 [= e1, e2] */
    record LocalStat(List<String> names, List<Expr> values, int line) implements Stat {}

    /** var1, var2 = e1, e2 */
    record AssignStat(List<Expr> targets, List<Expr> values, int line) implements Stat {}

    /** function a.b:c(...) body end */
    record FuncDefStat(String name, List<String> params, List<Stat> body, int endLine, int line) implements Stat {}

    /** local function name(...) body end */
    record LocalFuncStat(String name, List<String> params, List<Stat> body, int endLine, int line) implements Stat {}

    /** A function call used as a statement. */
    record CallStat(Expr expr, int line) implements Stat {}

    record DoStat(List<Stat> body, int endLine, int line) implements Stat {}
    record WhileStat(Expr cond, List<Stat> body, int endLine, int line) implements Stat {}
    record RepeatStat(List<Stat> body, Expr cond, int endLine, int line) implements Stat {}

    /**
     * elseBodyLine is the source line of the {@code else} keyword, or -1 if there is no else.
     * endLine is the source line of the closing {@code end} keyword.
     * Each IfClause carries the line of the {@code if}/{@code elseif} keyword that starts it.
     */
    record IfStat(List<IfClause> clauses, List<Stat> elseBody, int elseBodyLine, int endLine, int line) implements Stat {}
    record IfClause(Expr cond, List<Stat> body, int line) {}

    record ForNumStat(String var, Expr start, Expr limit, Expr step, List<Stat> body, int endLine, int line) implements Stat {}
    record ForInStat(List<String> names, List<Expr> iters, List<Stat> body, int endLine, int line) implements Stat {}
    record ReturnStat(List<Expr> values, int line) implements Stat {}
    record BreakStat(int line) implements Stat {}

    // Expressions

    sealed interface Expr permits
        NameExpr, StringExpr, NumberExpr, BoolExpr, NilExpr,
        VarArgExpr, FuncExpr, TableExpr,
        FieldExpr, IndexExpr, MethodCallExpr, CallExpr,
        BinOpExpr, UnOpExpr {}

    record NameExpr(String name) implements Expr {}
    record StringExpr(String value) implements Expr {}
    record NumberExpr(double value) implements Expr {}
    record BoolExpr(boolean value) implements Expr {}
    record NilExpr() implements Expr {}
    record VarArgExpr() implements Expr {}
    record FuncExpr(List<String> params, List<Stat> body) implements Expr {}
    record TableExpr(List<TableField> fields) implements Expr {}
    /** key == null means array-style entry */
    record TableField(Expr key, Expr value) {}

    /** a.b */
    record FieldExpr(Expr object, String field) implements Expr {}
    /** a[b] */
    record IndexExpr(Expr object, Expr key) implements Expr {}
    /** a:method(args) */
    record MethodCallExpr(Expr object, String method, List<Expr> args) implements Expr {}
    /** f(args) */
    record CallExpr(Expr func, List<Expr> args) implements Expr {}

    record BinOpExpr(String op, Expr left, Expr right) implements Expr {}
    record UnOpExpr(String op, Expr operand) implements Expr {}

    // Root

    record Chunk(List<Stat> body) {}
}
