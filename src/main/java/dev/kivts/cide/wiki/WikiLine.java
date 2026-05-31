package dev.kivts.cide.wiki;

public record WikiLine(String text, int color, boolean hr, int indent, boolean bullet) {

    // Palette
    public static final int C_H1   = 0xFFE2E8F4;
    public static final int C_H2   = 0xFFB4CCFF;
    public static final int C_SIG  = 0xFF4FC1FF;
    public static final int C_BODY = 0xFFADB8CC;
    public static final int C_CODE = 0xFFCE9178;

    public WikiLine(String text, int color, boolean hr, int indent) {
        this(text, color, hr, indent, false);
    }

    public static WikiLine h1(String text)     { return new WikiLine(text, C_H1,   false, 0); }
    public static WikiLine h2(String text)     { return new WikiLine(text, C_H2,   false, 0); }
    public static WikiLine sig(String text)    { return new WikiLine(text, C_SIG,  false, 1); }
    public static WikiLine body(String text)   { return new WikiLine(text, C_BODY, false, 0); }
    public static WikiLine bodyI(String text)  { return new WikiLine(text, C_BODY, false, 1); }
    public static WikiLine bullet(String text) { return new WikiLine(text, C_BODY, false, 0, true); }
    public static WikiLine code(String text)   { return new WikiLine(text, C_CODE, false, 2); }
    public static WikiLine rule()              { return new WikiLine("",   0,      true,  0); }
}
