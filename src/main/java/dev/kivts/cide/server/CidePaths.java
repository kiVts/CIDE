package dev.kivts.cide.server;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.regex.Pattern;

final class CidePaths {
    private static final Pattern MANY_DOTS = Pattern.compile("^[. ]+$");
    private static final char[] SPECIAL_CHARS = new char[]{ '"', '*', ':', '<', '>', '?', '|' };

    private CidePaths() {
    }

    static String sanitize(String rawPath) {
        String path = rawPath == null ? "" : rawPath.replace('\\', '/');
        StringBuilder cleanName = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c >= 32 && Arrays.binarySearch(SPECIAL_CHARS, c) < 0) cleanName.append(c);
        }

        ArrayDeque<String> outputParts = new ArrayDeque<>();
        for (String fullPart : cleanName.toString().split("/")) {
            String part = fullPart.strip();
            if (part.length() > 255) part = part.substring(0, 255).strip();
            if (part.equals("..")) {
                if (!outputParts.isEmpty()) outputParts.removeLast();
            } else if (part.isEmpty() || (part.startsWith(".") && MANY_DOTS.matcher(part).matches())) {
                continue;
            } else {
                outputParts.addLast(part);
            }
        }
        return String.join("/", outputParts);
    }
}
