package ir.layercloud.cortexdrone.harness.js;

import java.util.regex.Pattern;

/**
 * Compatibility preprocessor for the in-JVM Rhino runtime.
 *
 * <p>Rhino 1.7.15 is the newest release that still produces Java 8 bytecode
 * (required by Minecraft 1.7.10's class loader), but its ES6 support stops
 * short of the syntax modern models write. Instead of rejecting the script, the
 * harness normalizes the common, safe cases:</p>
 *
 * <ul>
 *   <li>{@code await}/{@code async}: tools are synchronous in this runtime, so
 *       the keywords are removed (the call still returns its result);</li>
 *   <li>{@code ?.}/{@code ??}: approximated as property access and {@code ||}
 *       because every tool result is an object;</li>
 *   <li>{@code for (const x of ...)}: rewritten to {@code for (var x of ...)};</li>
 *   <li>simple array/object spread and optional {@code catch} bindings.</li>
 * </ul>
 *
 * <p>All rewrites happen outside string literals, comments and regexes, and
 * preserve line numbers so Rhino errors still point at the right line.</p>
 */
public final class JsSource {
    private static final Pattern NULLISH = Pattern.compile("\\?\\?");
    private static final Pattern FOR_DECLARATION = Pattern.compile("\\bfor\\s*\\(\\s*(?:const|let)\\s+");
    private static final Pattern BLOCK_DECLARATION = Pattern.compile("\\b(?:const|let)\\s+(?=[A-Za-z_$])");
    private static final Pattern OPTIONAL_CATCH = Pattern.compile("\\bcatch\\s*\\{");
    private static final Pattern ARRAY_SPREAD = Pattern.compile("\\[\\.\\.\\.([A-Za-z_$][\\w$.]*)\\]");
    private static final Pattern OBJECT_SPREAD = Pattern.compile("\\{\\.\\.\\.([A-Za-z_$][\\w$.]*)\\}");

    private JsSource() {
    }

    public static String prepare(String source) {
        if (source == null || source.trim().isEmpty()) return "";
        String prepared = TypeScriptEraser.erase(source);
        prepared = stripAsyncKeywords(prepared);
        prepared = compat(prepared);
        return prepared;
    }

    private static String compat(String source) {
        String result = rewriteOptionalChaining(source);
        result = TypeScriptEraser.replaceOutsideLiterals(result, NULLISH, "||");
        result = TypeScriptEraser.replaceOutsideLiterals(result, FOR_DECLARATION, "for (var ");
        // Rhino rejects a second `const x` in the same function scope, and models
        // routinely reuse a name across two loops or blocks. `var` is
        // function-scoped and redeclarable, which matches what the script means.
        result = TypeScriptEraser.replaceOutsideLiterals(result, BLOCK_DECLARATION, "var ");
        result = TypeScriptEraser.replaceOutsideLiterals(result, OPTIONAL_CATCH, "catch (__error) {");
        result = TypeScriptEraser.replaceOutsideLiterals(result, ARRAY_SPREAD, "$1.slice()");
        result = TypeScriptEraser.replaceOutsideLiterals(result, OBJECT_SPREAD, "Object.assign({}, $1)");
        return result;
    }

    /**
     * Rewrites simple optional chains into calls to the safe helpers:
     * {@code a?.b} -> {@code __opt(a, "b")} and {@code a?.[i]} -> {@code __optIndex(a, i)}.
     * This preserves the null/undefined short-circuit for the common model patterns
     * while staying parseable by Rhino 1.7.15.
     */
    static String rewriteOptionalChaining(String source) {
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
                if (close < 0) { i += 2; continue; }
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
            if (mask.charAt(after) == '[') {
                out.append(',').append(suffix);
            } else {
                out.append(',').append(suffix);
            }
            lastExprOutStart = outStart;
            cursor = consumedTo;
            i = cursor;
        }
        out.append(source.substring(cursor));
        return out.toString();
    }

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
            if (dot >= 0 && mask.charAt(dot) == '.') {
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

    /**
     * Blanks {@code await}/{@code async} tokens (keeping length/newlines) when
     * they are used as keywords rather than identifiers or property names.
     */
    static String stripAsyncKeywords(String source) {
        String mask = TypeScriptEraser.maskLiterals(source);
        char[] output = source.toCharArray();
        int i = 0;
        while (i < mask.length()) {
            char c = mask.charAt(i);
            if (!isIdentifierStart(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < mask.length() && isIdentifierPart(mask.charAt(i))) i++;
            String token = mask.substring(start, i);
            if ((token.equals("await") && isAwaitKeyword(mask, start, i))
                    || (token.equals("async") && isAsyncKeyword(mask, start, i))) {
                blank(output, start, i);
            }
        }
        return new String(output);
    }

    private static boolean isAwaitKeyword(String mask, int start, int end) {
        int previous = previousSignificant(mask, start - 1);
        if (previous >= 0 && (isIdentifierPart(mask.charAt(previous)) || mask.charAt(previous) == '.')) {
            return false;
        }
        int next = skipWhitespace(mask, end);
        return next < mask.length();
    }

    private static boolean isAsyncKeyword(String mask, int start, int end) {
        int previous = previousSignificant(mask, start - 1);
        if (previous >= 0 && (isIdentifierPart(mask.charAt(previous)) || mask.charAt(previous) == '.')) {
            return false;
        }
        int next = skipWhitespace(mask, end);
        if (next >= mask.length()) return false;
        char c = mask.charAt(next);
        if (c == '(') return true;
        if (!isIdentifierStart(c)) return false;
        int wordStart = next;
        int wordEnd = next;
        while (wordEnd < mask.length() && isIdentifierPart(mask.charAt(wordEnd))) wordEnd++;
        if (mask.substring(wordStart, wordEnd).equals("function")) return true;
        int afterWord = skipWhitespace(mask, wordEnd);
        return afterWord < mask.length() && mask.charAt(afterWord) == '='
                && afterWord + 1 < mask.length() && mask.charAt(afterWord + 1) == '>';
    }

    private static void blank(char[] output, int start, int end) {
        for (int i = start; i < end; i++) {
            if (output[i] != '\n' && output[i] != '\r') output[i] = ' ';
        }
    }

    private static int skipWhitespace(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
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
