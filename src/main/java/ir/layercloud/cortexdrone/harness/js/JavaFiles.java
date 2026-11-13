package ir.layercloud.cortexdrone.harness.js;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns the strings scripts obtain from a code source into usable file paths.
 *
 * <p>The live script
 * {@code new java.io.File(url.getPath())} failed with
 * {@code InvalidPathException: Illegal char <:> at index 4} because
 * {@code getLocation()} returns a {@code file:/F:/...} URL and
 * {@code getPath()} keeps the {@code file:} scheme. On Windows the URL also
 * uses forward slashes and percent-encoding, and a jar entry URL ends in
 * {@code .jar!/com/...}.</p>
 *
 * <p>{@link #fileFrom(Object)} is bound as {@code toFile(...)}
 * in the script scope so a model can convert any of those forms in one step
 * instead of hand-decoding the URL.</p>
 */
final class JavaFiles {
    private JavaFiles() {
    }

    /**
     * A {@link File} for a path, URL or {@code URI}, or {@code null} when the
     * value cannot name a file.
     */
    static File fileFrom(Object value) {
        String text = toPath(normalize(value));
        return text == null || text.isEmpty() ? null : new File(text);
    }

    /**
     * Normalizes the string forms: strips {@code file:}, a {@code jar:} wrapper
     * and a {@code !/entry} suffix, decodes percent escapes and fixes Windows
     * separators and the leading slash ({@code /F:/...} -> {@code F:/...}).
     */
    static String toPath(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("jar:")) text = text.substring(4);
        int bang = text.indexOf("!/");
        if (bang >= 0) text = text.substring(0, bang);
        if (text.regionMatches(true, 0, "file:", 0, 5)) {
            text = text.substring(5);
            // file://host/path keeps the host only for UNC; two slashes are the
            // normal local form and both are stripped.
            while (text.startsWith("//")) text = text.substring(1);
        }
        try {
            text = URLDecoder.decode(text, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            // A % that is not an escape is a legal character on disk.
        }
        if (text.matches("^/[A-Za-z]:[/\\\\].*")) text = text.substring(1);
        return text;
    }

    /**
     * The raw string behind a Java value crossing into JS (URL, URI, File).
     */
    static String normalize(Object value) {
        if (value == null) return null;
        Object raw = JavaInterop.unwrapValue(value);
        if (raw == null) return null;
        if (raw instanceof URI) return ((URI) raw).toString();
        if (raw instanceof File) return ((File) raw).getPath();
        return String.valueOf(raw);
    }
}
