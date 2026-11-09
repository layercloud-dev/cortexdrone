package ir.layercloud.cortexdrone.harness.js;

/**
 * Rewrites JavaScript optional chaining into Rhino 1.7.15-compatible helper
 * calls.
 *
 * <p>{@code a?.b} becomes {@code __opt(a, "b")} and {@code a?.[i]} becomes
 * {@code __optIndex(a, i)}. This preserves the null/undefined short-circuit for
 * the patterns models actually write, while staying parseable by the Rhino
 * version Minecraft 1.7.10's class loader can host.</p>
 *
 * <p>The scanner works on a literal-masked copy so {@code ?.} inside a string,
 * comment or regex is never rewritten, and it preserves line numbers.</p>
 */
final class OptionalChainingRewriter {
    private OptionalChainingRewriter() {
    }

    /** Rewrites every optional chain in {@code source}. */
    static String rewrite(String source) {
        String mask = TypeScriptEraser.maskLiterals(source);
        StringBuilder out = new StringBuilder(source.length() + 32);
        int cursor = 0;
        int lastExprOutStart = -1;
        int i = 0;
        while (i < mask.length() - 1) {
            if (mask.charAt(i) != '?' || mask.charAt(i + 1) != '.') {
                i++;
                continue;
            }
            int exprStart = expressionStart(mask, i);
            if (exprStart < 0 || exprStart == i) {
                i += 2;
                continue;
            }
            int after = i + 2;
            String suffix;
            int consumedTo;
            if (after < mask.length() && mask.charAt(after) == '[') {
                int close = matchingForward(mask, after, '[', ']');
                if (close < 0) {
                    i += 2;
                    continue;
                }
                suffix = source.substring(after + 1, close) + ")";
                consumedTo = close + 1;
            } else if (after < mask.length() && isIdentifierStart(mask.charAt(after))) {
                int end = after;
                while (end < mask.length() && isIdentifierPart(mask.charAt(end))) end++;
                suffix = "\"" + source.substring(after, end) + "\")";
                consumedTo = end;
            } else {
                out.append(source, cursor, i).append('.');
                cursor = i + 2;
                i = cursor;
                continue;
            }

            int outStart;
            String expr;
            if (exprStart >= cursor) {
                out.append(source, cursor, exprStart);
                outStart = out.length();
                expr = source.substring(exprStart, i);
            } else {
                out.append(source, cursor, i);
                outStart = lastExprOutStart >= 0 ? lastExprOutStart : 0;
                expr = out.substring(outStart);
            }
            out.setLength(outStart);
            out.append(after < mask.length() && mask.charAt(after) == '['
                    ? "__optIndex(" : "__opt(").append(expr);
            out.append(',').append(suffix);
            lastExprOutStart = outStart;
            cursor = consumedTo;
            i = cursor;
        }
        out.append(source.substring(cursor));
        return out.toString();
    }

    /** Start index of the receiver expression that ends before {@code end}. */
    private static int expressionStart(String mask, int end) {
        int i = previousSignificant(mask, end - 1);
        if (i < 0) return -1;
        if (mask.charAt(i) == ')' || mask.charAt(i) == ']') {
            int open = matchingBackward(mask, i);
            if (open < 0) return -1;
            i = open;
            int before = previousSignificant(mask, open - 1);
            if (before >= 0 && (isIdentifierPart(mask.charAt(before))
                    || mask.charAt(before) == ')' || mask.charAt(before) == ']')) {
                i = expressionStart(mask, before + 1);
            }
        } else if (isIdentifierPart(mask.charAt(i))) {
            while (i > 0 && isIdentifierPart(mask.charAt(i - 1))) i--;
        } else {
            return -1;
        }
        // Include a preceding member/call chain: a.b, a[i], f(x).
        while (true) {
            int dot = previousSignificant(mask, i - 1);
            if (dot < 0 || mask.charAt(dot) != '.') break;
            int before = previousSignificant(mask, dot - 1);
            if (before < 0) break;
            if (mask.charAt(before) == ')' || mask.charAt(before) == ']') {
                int open = matchingBackward(mask, before);
                if (open < 0) break;
                i = open;
                int callee = previousSignificant(mask, open - 1);
                if (callee >= 0 && isIdentifierPart(mask.charAt(callee))) {
                    while (callee > 0 && isIdentifierPart(mask.charAt(callee - 1))) callee--;
                    i = callee;
                }
                continue;
            }
            if (isIdentifierPart(mask.charAt(before))) {
                while (before > 0 && isIdentifierPart(mask.charAt(before - 1))) before--;
                i = before;
                continue;
            }
            break;
        }
        return i;
    }

    private static int matchingForward(String text, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == opening) depth++;
            else if (c == closing && --depth == 0) return i;
        }
        return -1;
    }

    private static int matchingBackward(String text, int close) {
        char opening = text.charAt(close) == ']' ? '[' : '(';
        char closing = text.charAt(close);
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == closing) depth++;
            else if (c == opening && --depth == 0) return i;
        }
        return -1;
    }

    private static int previousSignificant(String text, int i) {
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
