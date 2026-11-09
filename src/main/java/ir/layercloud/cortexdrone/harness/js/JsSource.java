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
    private static final Pattern SPREAD_CALL =
            Pattern.compile("([A-Za-z_$][\\w$.]*)\\(\\s*\\.\\.\\.\\s*([A-Za-z_$][\\w$.]*)\\s*\\)");

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
        String result = OptionalChainingRewriter.rewrite(source);
        result = TypeScriptEraser.replaceOutsideLiterals(result, NULLISH, "||");
        result = TypeScriptEraser.replaceOutsideLiterals(result, FOR_DECLARATION, "for (var ");
        // Rhino rejects a second `const x` in the same function scope, and models
        // routinely reuse a name across two loops or blocks. `var` is
        // function-scoped and redeclarable, which matches what the script means.
        result = TypeScriptEraser.replaceOutsideLiterals(result, BLOCK_DECLARATION, "var ");
        result = TypeScriptEraser.replaceOutsideLiterals(result, OPTIONAL_CATCH, "catch (__error) {");
        result = TypeScriptEraser.replaceOutsideLiterals(result, ARRAY_SPREAD, "$1.slice()");
        result = TypeScriptEraser.replaceOutsideLiterals(result, OBJECT_SPREAD, "Object.assign({}, $1)");
        result = rewriteSpreadCalls(result);
        return result;
    }

    /**
     * Rewrites {@code f(...args)} / {@code obj.m(...args)} into
     * {@code apply} calls.
     *
     * <p>Rhino 1.7.15 parses array spread but not spread in call arguments, and
     * models write {@code Math.min(...xs)} constantly. Only a single spread that
     * is the whole argument list is rewritten; anything else is left for Rhino
     * to reject with a clear syntax error.</p>
     */
    static String rewriteSpreadCalls(String source) {
        String mask = TypeScriptEraser.maskLiterals(source);
        java.util.regex.Matcher matcher = SPREAD_CALL.matcher(mask);
        StringBuilder out = new StringBuilder(source.length() + 32);
        int last = 0;
        while (matcher.find()) {
            int start = matcher.start();
            // `new Foo(...args)` is a constructor, not a function call.
            if (start >= 4 && "new ".equals(source.substring(start - 4, start))) continue;
            String callee = matcher.group(1);
            String argument = matcher.group(2);
            out.append(source, last, start);
            int dot = callee.lastIndexOf('.');
            if (dot > 0) {
                String owner = callee.substring(0, dot);
                out.append(callee).append(".apply(").append(owner).append(", ").append(argument).append(")");
            } else {
                out.append(callee).append(".apply(null, ").append(argument).append(")");
            }
            last = matcher.end();
        }
        out.append(source.substring(last));
        return out.toString();
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
