package ir.layercloud.cortexdrone;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Reads production sources for the structural guard tests.
 *
 * <p>These guards assert that a rule exists somewhere in a layer, not that it
 * lives in one specific file. Anchoring them to a file name made every
 * decomposition look like a regression; anchoring them to a package lets the
 * code be split without weakening the rule.</p>
 */
public final class SourceTree {
    public static final String MAIN = "src/main/java/ir/layercloud/cortexdrone/";
    public static final String RESOURCES = "src/main/resources/";

    private SourceTree() {
    }

    /**
     * Reads one file, or returns an empty string when it is absent.
     */
    public static String read(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Reads one file; fails loudly when it is absent.
     */
    public static String require(String path) {
        String text = read(path);
        if (text.isEmpty()) {
            throw new IllegalStateException("Source not found: " + path);
        }
        return text;
    }

    /**
     * Concatenates every {@code .java} file under {@code dir} (recursively).
     *
     * <p>Use this when the assertion is about behaviour that may legitimately
     * move between classes in the same layer.</p>
     */
    public static String readPackage(String dir) {
        StringBuilder out = new StringBuilder();
        collect(new File(dir), out);
        return out.toString();
    }

    private static void collect(File file, StringBuilder out) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            if (file.getName().endsWith(".java")) out.append(read(file.getPath())).append('\n');
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File child : children) collect(child, out);
    }
}
