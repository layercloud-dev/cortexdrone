package ir.layercloud.cortexdrone.harness.js;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort TypeScript type erasure.
 *
 * <p>There is no TypeScript compiler inside the JVM, so CortexDrone accepts the
 * common "typed JavaScript" subset: primitive/uppercase type annotations,
 * {@code as} casts, {@code interface}/{@code type} declarations and simple
 * generic parameters are erased before Rhino parses the script. Anything more
 * exotic produces a clear Rhino syntax error, which the model can fix.</p>
 */
final class TypeScriptEraser {
    private static final Pattern VARIABLE_ANNOTATION = Pattern.compile(
            "\\b(let|const|var)\\s+([A-Za-z_$][\\w$]*)\\s*:\\s*" + type() + "\\s*(?==)");
    // A return-type annotation may not be a bare null/undefined: those are the
    // else-branch of a ternary ("cond ? a : null,") and erasing them produced
    // "missing : in conditional expression" on ordinary JavaScript.
    private static final Pattern PARAM_OR_RETURN_ANNOTATION = Pattern.compile(
            "\\s*:\\s*" + nonNullishAnnotationType() + "\\s*(?=[,)=]|=>|\\{)");
    private static final Pattern AS_CAST = Pattern.compile(
            "\\s+as\\s+" + type() + "(?=\\s|[,);}\\]])");
    private static final Pattern GENERIC_FUNCTION = Pattern.compile(
            "\\b(function\\s+[A-Za-z_$][\\w$]*)\\s*<[^<>{};]*>");
    private static final Pattern IMPLEMENTS = Pattern.compile(
            "\\s+implements\\s+[A-Za-z_$][\\w$.,<>\\s]*(?=\\{)");

    private TypeScriptEraser() {
    }

    static String erase(String source) {
        if (source == null || source.isEmpty()) return "";
        String result = stripBlocks(source);
        result = replaceOutsideLiterals(result, VARIABLE_ANNOTATION, "$1 $2 ");
        result = replaceOutsideLiterals(result, PARAM_OR_RETURN_ANNOTATION, " ");
        result = replaceOutsideLiterals(result, AS_CAST, " ");
        result = replaceOutsideLiterals(result, GENERIC_FUNCTION, "$1");
        result = replaceOutsideLiterals(result, IMPLEMENTS, " ");
        return result;
    }

    private static String type() {
        return "(?:void|string|number|boolean|any|unknown|never|object|null|undefined"
                + "|[A-Z][\\w$.]*(?:\\s*<[^<>{};]*>)?(?:\\s*\\[\\])*)"
                + "(?:\\s*\\|\\s*(?:[A-Za-z_$][\\w$.]*(?:\\s*<[^<>{};]*>)?(?:\\s*\\[\\])*))*";
    }

    /**
     * A type name that cannot also be an object-literal value: no dotted
     * namespace access and at least one lowercase letter. This keeps
     * {@code { max_wait_ms: LIMITS.maxWaitMs }} from being read as a type
     * annotation and rewritten to a shorthand key.
     */
    private static String nonNullishAnnotationType() {
        return "(?:void|string|number|boolean|any|unknown|never|object"
                + "|[A-Z][\\w$]*[a-z][\\w$]*(?:\\s*<[^<>{};]*>)?(?:\\s*\\[\\])*)"
                + "(?:\\s*\\|\\s*(?:[A-Za-z_$][\\w$]*(?:\\s*<[^<>{};]*>)?(?:\\s*\\[\\])*))*";
    }


    /**
     * Removes entire {@code interface X {...}}, {@code type X = ...;} and {@code declare ...;} statements.
     */
    private static String stripBlocks(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            int tokenStart = statementTokenAt(source, i);
            if (tokenStart < 0) {
                out.append(source.charAt(i++));
                continue;
            }
            String token = readIdentifier(source, tokenStart);
            int afterToken = skipWhitespace(source, tokenStart + token.length());
            if (token.equals("interface") || token.equals("declare")) {
                int open = source.indexOf('{', afterToken);
                int semicolon = source.indexOf(';', afterToken);
                if (open >= 0 && (semicolon < 0 || open < semicolon)) {
                    int close = matchingBrace(source, open);
                    i = close > open ? close + 1 : source.length();
                    continue;
                }
                if (semicolon >= 0) {
                    i = semicolon + 1;
                    continue;
                }
            }
            if (token.equals("type") && afterToken < source.length()
                    && isIdentifierStart(source.charAt(afterToken))) {
                int equals = source.indexOf('=', afterToken);
                int newline = source.indexOf('\n', afterToken);
                int end = equals >= 0 ? source.indexOf(';', equals) : -1;
                if (end < 0) end = newline >= 0 ? newline : source.length();
                if (equals >= 0 && equals < end) {
                    i = end + (end < source.length() && source.charAt(end) == ';' ? 1 : 0);
                    continue;
                }
            }
            out.append(source.charAt(i++));
        }
        return out.toString();
    }

    /**
     * Returns the index when a statement-level keyword starts at {@code i}, else -1.
     */
    private static int statementTokenAt(String source, int i) {
        if (!isIdentifierStart(source.charAt(i))) return -1;
        int previous = previousSignificant(source, i - 1);
        if (previous >= 0 && (isIdentifierPart(source.charAt(previous))
                || source.charAt(previous) == '.')) return -1;
        return i;
    }

    private static String readIdentifier(String source, int start) {
        int end = start;
        while (end < source.length() && isIdentifierPart(source.charAt(end))) end++;
        return source.substring(start, end);
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static int skipWhitespace(String source, int i) {
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
        return i;
    }

    private static int previousSignificant(String source, int i) {
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * Applies a regex only outside string literals, template literals, regexes and comments.
     */
    static String replaceOutsideLiterals(String source, Pattern pattern, String replacement) {
        String mask = maskLiterals(source);
        Matcher matcher = pattern.matcher(mask);
        StringBuilder out = new StringBuilder(source.length());
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() < cursor) continue;
            out.append(source, cursor, matcher.start());
            String expanded = replacement;
            for (int group = matcher.groupCount(); group >= 1; group--) {
                String value = matcher.group(group);
                expanded = expanded.replace("$" + group, value != null ? value : "");
            }
            out.append(expanded);
            cursor = matcher.end();
        }
        out.append(source.substring(cursor));
        return out.toString();
    }

    /**
     * Replaces the inner content of literals/comments with spaces while keeping
     * every index and newline intact.
     */
    static String maskLiterals(String source) {
        char[] masked = source.toCharArray();
        int i = 0;
        char previous = '\0';
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') masked[i++] = ' ';
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                masked[i++] = ' ';
                masked[i++] = ' ';
                while (i < source.length()) {
                    if (source.charAt(i) == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                        masked[i++] = ' ';
                        masked[i++] = ' ';
                        break;
                    }
                    if (source.charAt(i) != '\n') masked[i] = ' ';
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                char quote = c;
                masked[i++] = ' ';
                while (i < source.length()) {
                    char inner = source.charAt(i);
                    if (inner == '\\' && i + 1 < source.length()) {
                        masked[i++] = ' ';
                        if (i < source.length() && source.charAt(i) != '\n') masked[i] = ' ';
                        i++;
                        continue;
                    }
                    if (inner == quote) {
                        masked[i++] = ' ';
                        break;
                    }
                    if (inner != '\n') masked[i] = ' ';
                    i++;
                }
                continue;
            }
            if (c == '/' && looksLikeRegex(previous)) {
                masked[i++] = ' ';
                boolean inClass = false;
                while (i < source.length()) {
                    char inner = source.charAt(i);
                    if (inner == '\\' && i + 1 < source.length()) {
                        masked[i++] = ' ';
                        if (source.charAt(i) != '\n') masked[i] = ' ';
                        i++;
                        continue;
                    }
                    if (inner == '[') inClass = true;
                    else if (inner == ']') inClass = false;
                    else if (inner == '/' && !inClass) {
                        masked[i++] = ' ';
                        break;
                    }
                    if (inner != '\n') masked[i] = ' ';
                    i++;
                }
                continue;
            }
            if (!Character.isWhitespace(c)) previous = c;
            i++;
        }
        return new String(masked);
    }

    private static boolean looksLikeRegex(char previous) {
        return previous == '\0' || "=(,:[!&|?{};+-*%<>~^".indexOf(previous) >= 0;
    }
}
