package ir.layercloud.cortexdrone.config;

import java.io.File;

/**
 * Canonical file layout for the harness.
 *
 * <pre>
 * config/cortexdrone/            harness state, survives a world reset
 *   config.json   LLM gateway, agent limits, saved drone bindings
 *   skills/       one directory per learned skill (SKILL.md + code.js)
 *   lessons/      one LESSON.md directory per learned rule
 *   sessions/     per-task transcripts grouped by outcome
 *   logs/         debug log
 *
 * world/cortexdrone/             world state, travels with the save
 *   memory.json           player facts and named locations
 *   protected-zones.json  player-owned no-break cylinders
 * </pre>
 */
@SuppressWarnings("unused")
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

    /**
     * Learned/refined skills, one SKILL.md directory per skill.
     */
    public static File skillsDir(File root) {
        return new File(root, "skills");
    }

    /**
     * World state root, inside the save so it moves with the world.
     */
    public static File worldRoot(File worldDirectory) {
        File base = worldDirectory != null ? worldDirectory : new File("world");
        return new File(base, "cortexdrone");
    }

    public static File memoryFile(File root) {
        return new File(root, "memory.json");
    }

    /**
     * Player-owned no-break cylinders, world-scoped.
     */
    public static File protectedZonesFile(File root) {
        return new File(root, "protected-zones.json");
    }

    /**
     * Learned lessons, one LESSON.md directory per lesson.
     */
    public static File lessonsDir(File root) {
        return new File(root, "lessons");
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
