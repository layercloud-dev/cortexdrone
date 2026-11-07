package ir.layercloud.cortexdrone.config;

import java.io.File;

/**
 * Canonical file layout for the harness.
 *
 * <pre>
 * config/cortexdrone/
 *   config.json   LLM gateway, agent limits, saved drone bindings
 *   skills.json   learned + bundled skill overrides
 *   memory.json   player facts and named locations
 *   lessons.json  learned procedural rules
 *   logs/         debug log
 * </pre>
 */
public final class ModPaths {
    private ModPaths() {
    }

    public static File root(File minecraftConfigDir) {
        File base = minecraftConfigDir != null ? minecraftConfigDir : new File("config");
        return new File(base, "cortexdrone");
    }

    public static File configFile(File root) {
        return new File(root, "config.json");
    }

    public static File skillsFile(File root) {
        return new File(root, "skills.json");
    }

    public static File memoryFile(File root) {
        return new File(root, "memory.json");
    }

    public static File lessonsFile(File root) {
        return new File(root, "lessons.json");
    }

    public static File logFile(File root) {
        return new File(root, "logs/cortexdrone.log");
    }

    public static File ensure(File root) {
        if (root != null && !root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("Cannot create config directory " + root.getAbsolutePath());
        }
        File logs = new File(root, "logs");
        if (!logs.exists() && !logs.mkdirs()) {
            throw new IllegalStateException("Cannot create log directory " + logs.getAbsolutePath());
        }
        return root;
    }
}
