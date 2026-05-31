package dev.kivts.cide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fault-tolerant recursive-descent Lua 5.2 parser.
 *
 * On any parse error the offending statement is skipped and parsing continues
 * from the next recognisable boundary, so a file that is mid-edit always
 * produces the best possible partial AST rather than nothing at all.
 */
final class LuaParser {

    // Token type constants

    static final int T_NAME   = 1;
    static final int T_STRING = 2;
    static final int T_NUMBER = 3;
    static final int T_EOF    = 0;
    static final int T_OTHER  = 5;

    record Tok(int type, String val, int line) {
        static final Tok EOF = new Tok(T_EOF, "<eof>", -1);
    }

    // Tokenizer

    static List<Tok> lex(List<String> srcLines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < srcLines.size(); i++) {
            sb.append(srcLines.get(i));
            if (i < srcLines.size() - 1) sb.append('\n');
        }
        return lex(sb.toString());
    }

    static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        int line = 0; // current 0-based line

        outer:
        while (i < n) {
            char c = src.charAt(i);

            // Whitespace (track newlines)
            if (c <= ' ') { if (c == '\n') line++; i++; continue; }

            // Comments  --  or  --[=[...]=]
            if (c == '-' && i + 1 < n && src.charAt(i + 1) == '-') {
                i += 2;
                if (i < n && src.charAt(i) == '[') {
                    int lvl = longBracketLevel(src, i);
                    if (lvl >= 0) {
                        // count newlines inside long comment
                        int end = skipLong(src, i, lvl);
                        for (int j = i; j < end; j++) if (src.charAt(j) == '\n') line++;
                        i = end; continue;
                    }
                }
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }

            // Long string  [=[...]=]
            if (c == '[') {
                int lvl = longBracketLevel(src, i);
                if (lvl >= 0) {
                    int hd   = 2 + lvl;
                    int body = i + hd;
                    int end  = skipLong(src, i, lvl);
                    String raw = src.substring(body, end - hd);
                    if (!raw.isEmpty() && raw.charAt(0) == '\n') raw = raw.substring(1);
                    out.add(new Tok(T_STRING, raw, line));
                    for (int j = i; j < end; j++) if (src.charAt(j) == '\n') line++;
                    i = end;
                    continue;
                }
            }

            // Quoted string  "..."  '...'
            if (c == '"' || c == '\'') {
                int tokLine = line;
                char q = c;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    char sc = src.charAt(i);
                    if (sc == q) { i++; break; }
                    if (sc == '\n' || sc == '\r') break; // unterminated
                    if (sc == '\\' && i + 1 < n) {
                        i++;
                        switch (src.charAt(i)) {
                            case 'n'  -> sb.append('\n');
                            case 't'  -> sb.append('\t');
                            case 'r'  -> sb.append('\r');
                            case '\\' -> sb.append('\\');
                            case '\'' -> sb.append('\'');
                            case '"'  -> sb.append('"');
                            case 'a'  -> sb.append('\007');
                            case 'b'  -> sb.append('\b');
                            case 'f'  -> sb.append('\f');
                            case 'v'  -> sb.append('\013');
                            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                                int val = src.charAt(i) - '0';
                                if (i + 1 < n && Character.isDigit(src.charAt(i + 1)))
                                    val = val * 10 + (src.charAt(++i) - '0');
                                if (i + 1 < n && Character.isDigit(src.charAt(i + 1)))
                                    val = val * 10 + (src.charAt(++i) - '0');
                                sb.append((char) val);
                            }
                            default -> sb.append(src.charAt(i));
                        }
                        i++;
                    } else {
                        sb.append(sc);
                        i++;
                    }
                }
                out.add(new Tok(T_STRING, sb.toString(), tokLine));
                continue;
            }

            // Numbers
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                int s = i;
                if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
                    i += 2;
                    while (i < n && isHex(src.charAt(i))) i++;
                } else {
                    while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                    if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                        i++;
                        if (i < n && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
                        while (i < n && Character.isDigit(src.charAt(i))) i++;
                    }
                }
                out.add(new Tok(T_NUMBER, src.substring(s, i), line));
                continue;
            }

            // Identifiers and keywords (all lexed as T_NAME; parser interprets)
            if (Character.isLetter(c) || c == '_') {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                out.add(new Tok(T_NAME, src.substring(s, i), line));
                continue;
            }

            // Three-char operators
            if (i + 2 < n) {
                String t3 = src.substring(i, i + 3);
                if (t3.equals("...")) { out.add(new Tok(T_OTHER, "...", line)); i += 3; continue; }
            }

            // Two-char operators
            if (i + 1 < n) {
                String t2 = src.substring(i, i + 2);
                switch (t2) {
                    case "==", "~=", "<=", ">=", "..", "::", "<<", ">>" -> {
                        out.add(new Tok(T_OTHER, t2, line)); i += 2; continue outer;
                    }
                }
            }

            // Single-char token
            out.add(new Tok(T_OTHER, String.valueOf(c), line));
            i++;
        }

        out.add(Tok.EOF);
        return out;
    }

    private static int longBracketLevel(String src, int i) {
        if (i >= src.length() || src.charAt(i) != '[') return -1;
        int lvl = 0, j = i + 1;
        while (j < src.length() && src.charAt(j) == '=') { lvl++; j++; }
        return (j < src.length() && src.charAt(j) == '[') ? lvl : -1;
    }

    private static int skipLong(String src, int i, int lvl) {
        i += 2 + lvl; // skip [=..=[
        String close = "]" + "=".repeat(lvl) + "]";
        int end = src.indexOf(close, i);
        return end < 0 ? src.length() : end + close.length();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // Parser

    private final List<Tok> toks;
    private int pos;

    private LuaParser(List<Tok> toks) { this.toks = toks; this.pos = 0; }

    /** Parse a list of source lines into a Chunk. Never throws. */
    public static LuaAst.Chunk parse(List<String> lines) {
        try {
            return new LuaParser(lex(lines)).parseChunk();
        } catch (Exception e) {
            return new LuaAst.Chunk(List.of());
        }
    }

    // Token access

    private Tok cur()                 { return pos < toks.size() ? toks.get(pos) : Tok.EOF; }
    private Tok peek(int offset)      { int p = pos + offset; return p < toks.size() ? toks.get(p) : Tok.EOF; }
    private Tok consume()             { Tok t = cur(); if (cur().type() != T_EOF) pos++; return t; }
    private boolean check(String v)   { return cur().val().equals(v); }
    private boolean checkType(int t)  { return cur().type() == t; }
    private boolean tryConsume(String v) { if (check(v)) { consume(); return true; } return false; }
    private Tok expect(String v) {
        if (check(v)) return consume();
        throw new ParseErr("expected '" + v + "' got '" + cur().val() + "'");
    }
    private String expectName() {
        if (checkType(T_NAME)) return consume().val();
        throw new ParseErr("expected name got '" + cur().val() + "'");
    }

    private static class ParseErr extends RuntimeException {
        ParseErr(String m) { super(m, null, true, false); }
    }

    // Keywords that end a block

    private static final Set<String> BLOCK_END = Set.of("end", "else", "elseif", "until");

    // Keywords that start a new statement - used for error recovery
    private static final Set<String> STAT_START = Set.of(
        "local", "function", "if", "while", "for", "do", "repeat",
        "return", "break", "goto", "::", "end", "else", "elseif", "until"
    );

    // Grammar

    private LuaAst.Chunk parseChunk() {
        List<LuaAst.Stat> body = parseBlock();
        return new LuaAst.Chunk(body);
    }

    private List<LuaAst.Stat> parseBlock() {
        List<LuaAst.Stat> stats = new ArrayList<>();
        while (true) {
            while (tryConsume(";")) {}
            if (cur().type() == T_EOF || BLOCK_END.contains(cur().val())) break;
            if (check("return")) {
                stats.add(parseReturn());
                tryConsume(";");
                if (cur().type() == T_EOF || BLOCK_END.contains(cur().val())) break;
                continue;
            }

            int savedPos = pos;
            try {
                LuaAst.Stat s = parseStat();
                if (s != null) stats.add(s);
            } catch (ParseErr e) {
                if (pos == savedPos) consume(); // guarantee progress
                recover();
            }
        }
        return stats;
    }

    /** Skip tokens until we reach something that looks like a new statement or block end. */
    private void recover() {
        while (cur().type() != T_EOF) {
            if (STAT_START.contains(cur().val())) break;
            consume();
        }
    }

    private LuaAst.Stat parseStat() {
        String v = cur().val();
        int line = cur().line();
        return switch (v) {
            case "local"    -> parseLocal(line);
            case "function" -> parseFuncDef(line);
            case "do"       -> { consume(); var b = parseBlock(); int el = consumeBlockEnd("end"); yield new LuaAst.DoStat(b, el, line); }
            case "while"    -> parseWhile(line);
            case "repeat"   -> parseRepeat(line);
            case "if"       -> parseIf(line);
            case "for"      -> parseFor(line);
            case "goto"     -> { consume(); consume(); yield null; }
            case "::"       -> { consume(); consume(); expect("::"); yield null; }
            case "break"    -> { consume(); yield new LuaAst.BreakStat(line); }
            default         -> parseExprStat(line);
        };
    }

    // local name [...] [= explist]  |  local function name funcbody
    private LuaAst.Stat parseLocal(int line) {
        expect("local");
        if (check("function")) {
            consume();
            String name = expectName();
            var fb = parseFuncBody();
            return new LuaAst.LocalFuncStat(name, fb.params(), fb.body(), fb.endLine(), line);
        }
        List<String> names = new ArrayList<>();
        names.add(expectName());
        while (tryConsume(",")) names.add(expectName());
        List<LuaAst.Expr> vals = tryConsume("=") ? parseExpList() : List.of();
        return new LuaAst.LocalStat(names, vals, line);
    }

    // function funcname funcbody
    private LuaAst.Stat parseFuncDef(int line) {
        expect("function");
        StringBuilder name = new StringBuilder(expectName());
        while (tryConsume(".")) name.append('.').append(expectName());
        if (tryConsume(":")) name.append(':').append(expectName());
        var fb = parseFuncBody();
        return new LuaAst.FuncDefStat(name.toString(), fb.params(), fb.body(), fb.endLine(), line);
    }

    private record FuncBody(List<String> params, List<LuaAst.Stat> body, int endLine) {}

    private FuncBody parseFuncBody() {
        expect("(");
        List<String> params = new ArrayList<>();
        if (!check(")")) {
            if (check("...")) { consume(); }
            else {
                params.add(expectName());
                while (tryConsume(",")) {
                    if (check("...")) { consume(); break; }
                    params.add(expectName());
                }
            }
        }
        expect(")");
        List<LuaAst.Stat> body = parseBlock();
        int endLine = consumeBlockEnd("end");
        return new FuncBody(params, body, endLine);
    }

    private LuaAst.Stat parseWhile(int line) {
        expect("while");
        LuaAst.Expr cond = parseExpr();
        expect("do");
        List<LuaAst.Stat> body = parseBlock();
        int endLine = consumeBlockEnd("end");
        return new LuaAst.WhileStat(cond, body, endLine, line);
    }

    private LuaAst.Stat parseRepeat(int line) {
        expect("repeat");
        List<LuaAst.Stat> body = parseBlock();
        int endLine = consumeBlockEnd("until");
        LuaAst.Expr cond = parseExpr();
        return new LuaAst.RepeatStat(body, cond, endLine, line);
    }

    private LuaAst.Stat parseIf(int line) {
        expect("if");
        List<LuaAst.IfClause> clauses = new ArrayList<>();
        clauses.add(new LuaAst.IfClause(parseExpr(), parseBody("then"), line));
        while (check("elseif")) {
            int elseifLine = cur().line();
            consume();
            clauses.add(new LuaAst.IfClause(parseExpr(), parseBody("then"), elseifLine));
        }
        int elseBodyLine = -1;
        List<LuaAst.Stat> elseBody = List.of();
        if (check("else")) {
            elseBodyLine = cur().line();
            consume();
            elseBody = parseBlock();
        }
        int endLine = consumeBlockEnd("end");
        return new LuaAst.IfStat(clauses, elseBody, elseBodyLine, endLine, line);
    }

    private List<LuaAst.Stat> parseBody(String keyword) {
        expect(keyword);
        return parseBlock();
    }

    private LuaAst.Stat parseFor(int line) {
        expect("for");
        String first = expectName();
        if (tryConsume("=")) {
            LuaAst.Expr start = parseExpr();
            expect(",");
            LuaAst.Expr limit = parseExpr();
            LuaAst.Expr step  = tryConsume(",") ? parseExpr() : null;
            expect("do");
            List<LuaAst.Stat> body = parseBlock();
            int endLine = consumeBlockEnd("end");
            return new LuaAst.ForNumStat(first, start, limit, step, body, endLine, line);
        }
        // generic for
        List<String> names = new ArrayList<>();
        names.add(first);
        while (tryConsume(",")) names.add(expectName());
        expect("in");
        List<LuaAst.Expr> iters = parseExpList();
        expect("do");
        List<LuaAst.Stat> body = parseBlock();
        int endLine = consumeBlockEnd("end");
        return new LuaAst.ForInStat(names, iters, body, endLine, line);
    }

    /** Consumes the block-end keyword and returns its source line (or MAX_VALUE on EOF). */
    private int consumeBlockEnd(String keyword) {
        if (cur().type() == T_EOF) return Integer.MAX_VALUE;
        int line = cur().line();
        expect(keyword);
        return line;
    }

    private LuaAst.Stat parseReturn() {
        int line = cur().line();
        expect("return");
        String v = cur().val();
        boolean atEnd = cur().type() == T_EOF || BLOCK_END.contains(v) || v.equals(";");
        List<LuaAst.Expr> vals = atEnd ? List.of() : parseExpList();
        return new LuaAst.ReturnStat(vals, line);
    }

    /** Assignment or function-call expression statement. */
    private LuaAst.Stat parseExprStat(int line) {
        LuaAst.Expr first = parseSuffixedExpr();

        if (check("=") || check(",")) {
            List<LuaAst.Expr> targets = new ArrayList<>();
            targets.add(first);
            while (tryConsume(",")) targets.add(parseSuffixedExpr());
            expect("=");
            return new LuaAst.AssignStat(targets, parseExpList(), line);
        }

        return new LuaAst.CallStat(first, line);
    }

    // Expressions

    private List<LuaAst.Expr> parseExpList() {
        List<LuaAst.Expr> list = new ArrayList<>();
        list.add(parseExpr());
        while (tryConsume(",")) list.add(parseExpr());
        return list;
    }

    private LuaAst.Expr parseExpr() { return parseOr(); }

    private LuaAst.Expr parseOr() {
        LuaAst.Expr l = parseAnd();
        while (check("or"))  { consume(); l = new LuaAst.BinOpExpr("or",  l, parseAnd()); }
        return l;
    }
    private LuaAst.Expr parseAnd() {
        LuaAst.Expr l = parseCompar();
        while (check("and")) { consume(); l = new LuaAst.BinOpExpr("and", l, parseCompar()); }
        return l;
    }
    private LuaAst.Expr parseCompar() {
        LuaAst.Expr l = parseBitor();
        while (check("<") || check(">") || check("<=") || check(">=") || check("==") || check("~=")) {
            l = new LuaAst.BinOpExpr(consume().val(), l, parseBitor());
        }
        return l;
    }
    private LuaAst.Expr parseBitor() {
        LuaAst.Expr l = parseBitxor();
        while (check("|")) { consume(); l = new LuaAst.BinOpExpr("|", l, parseBitxor()); }
        return l;
    }
    private LuaAst.Expr parseBitxor() {
        LuaAst.Expr l = parseBitand();
        // ~ alone is bitwise-xor in Lua 5.3; ~= is a distinct two-char token
        while (check("~")) { consume(); l = new LuaAst.BinOpExpr("~", l, parseBitand()); }
        return l;
    }
    private LuaAst.Expr parseBitand() {
        LuaAst.Expr l = parseBitshift();
        while (check("&")) { consume(); l = new LuaAst.BinOpExpr("&", l, parseBitshift()); }
        return l;
    }
    private LuaAst.Expr parseBitshift() {
        LuaAst.Expr l = parseConcat();
        while (check("<<") || check(">>")) { l = new LuaAst.BinOpExpr(consume().val(), l, parseConcat()); }
        return l;
    }
    private LuaAst.Expr parseConcat() {
        LuaAst.Expr l = parseAdd();
        if (check("..")) { consume(); return new LuaAst.BinOpExpr("..", l, parseConcat()); } // right-assoc
        return l;
    }
    private LuaAst.Expr parseAdd() {
        LuaAst.Expr l = parseMul();
        while (check("+") || check("-")) { l = new LuaAst.BinOpExpr(consume().val(), l, parseMul()); }
        return l;
    }
    private LuaAst.Expr parseMul() {
        LuaAst.Expr l = parseUnary();
        while (check("*") || check("/") || check("%") || check("//")) {
            l = new LuaAst.BinOpExpr(consume().val(), l, parseUnary());
        }
        return l;
    }
    private LuaAst.Expr parseUnary() {
        if (check("not")) { consume(); return new LuaAst.UnOpExpr("not", parseUnary()); }
        if (check("-"))   { consume(); return new LuaAst.UnOpExpr("-",   parseUnary()); }
        if (check("#"))   { consume(); return new LuaAst.UnOpExpr("#",   parseUnary()); }
        if (check("~"))   { consume(); return new LuaAst.UnOpExpr("~",   parseUnary()); }
        return parsePower();
    }
    private LuaAst.Expr parsePower() {
        LuaAst.Expr l = parseSuffixedExpr();
        if (check("^")) { consume(); return new LuaAst.BinOpExpr("^", l, parseUnary()); } // right-assoc
        return l;
    }

    /** Primary expression plus zero or more field/index/call suffixes. */
    private LuaAst.Expr parseSuffixedExpr() {
        LuaAst.Expr e = parsePrimary();
        while (true) {
            if (check(".")) {
                consume(); e = new LuaAst.FieldExpr(e, expectName());
            } else if (check("[")) {
                consume(); LuaAst.Expr k = parseExpr(); expect("]"); e = new LuaAst.IndexExpr(e, k);
            } else if (check(":")) {
                consume(); String m = expectName(); e = new LuaAst.MethodCallExpr(e, m, parseCallArgs());
            } else if (check("(") || check("{") || checkType(T_STRING)) {
                e = new LuaAst.CallExpr(e, parseCallArgs());
            } else {
                break;
            }
        }
        return e;
    }

    private LuaAst.Expr parsePrimary() {
        if (checkType(T_NAME)) {
            String name = consume().val();
            // Treat keyword atoms as their own expression types
            return switch (name) {
                case "nil"   -> new LuaAst.NilExpr();
                case "true"  -> new LuaAst.BoolExpr(true);
                case "false" -> new LuaAst.BoolExpr(false);
                case "..."   -> new LuaAst.VarArgExpr();
                case "function" -> { var fb = parseFuncBody(); yield new LuaAst.FuncExpr(fb.params(), fb.body()); }
                default      -> new LuaAst.NameExpr(name);
            };
        }
        if (check("(")) {
            consume(); LuaAst.Expr e = parseExpr(); expect(")"); return e;
        }
        if (checkType(T_STRING)) return new LuaAst.StringExpr(consume().val());
        if (checkType(T_NUMBER)) {
            String raw = consume().val();
            try { return new LuaAst.NumberExpr(Double.parseDouble(raw)); }
            catch (NumberFormatException ignored) { return new LuaAst.NumberExpr(0); }
        }
        if (check("{"))    return parseTableCtor();
        if (check("..."))  { consume(); return new LuaAst.VarArgExpr(); }
        if (check("function")) { consume(); var fb = parseFuncBody(); return new LuaAst.FuncExpr(fb.params(), fb.body()); }

        // Fallback: consume the unexpected token and return nil to avoid stalling
        consume();
        return new LuaAst.NilExpr();
    }

    private List<LuaAst.Expr> parseCallArgs() {
        List<LuaAst.Expr> args = new ArrayList<>();
        if (check("(")) {
            consume();
            if (!check(")")) args = parseExpList();
            expect(")");
        } else if (check("{")) {
            args.add(parseTableCtor());
        } else if (checkType(T_STRING)) {
            args.add(new LuaAst.StringExpr(consume().val()));
        }
        return args;
    }

    private LuaAst.Expr parseTableCtor() {
        expect("{");
        List<LuaAst.TableField> fields = new ArrayList<>();
        while (!check("}") && cur().type() != T_EOF) {
            if (check("[")) {
                consume();
                LuaAst.Expr key = parseExpr();
                expect("]"); expect("=");
                fields.add(new LuaAst.TableField(key, parseExpr()));
            } else if (checkType(T_NAME) && peek(1).val().equals("=")) {
                LuaAst.Expr key = new LuaAst.StringExpr(consume().val());
                consume(); // =
                fields.add(new LuaAst.TableField(key, parseExpr()));
            } else {
                fields.add(new LuaAst.TableField(null, parseExpr()));
            }
            if (!tryConsume(",") && !tryConsume(";")) break;
        }
        tryConsume("}"); // lenient - might be missing on partial edits
        return new LuaAst.TableExpr(fields);
    }
}
