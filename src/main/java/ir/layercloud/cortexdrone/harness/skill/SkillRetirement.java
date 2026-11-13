package ir.layercloud.cortexdrone.harness.skill;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Retirement of learned skills without destroying them.
 *
 * <p>A learned skill lives in {@code skills/<name>/}. Retiring it moves the whole
 * directory to {@code skills-disabled/<name>/}, where it is kept on disk but
 * never loaded. Enabling moves it back. This keeps the library list clean
 * without forcing an operator to delete code they may want to repair later.</p>
 *
 * <p>Extracted from {@link SkillLibrary} so the library stays under the modular
 * line ceiling and the directory move has one owner.</p>
 */
final class SkillRetirement {
    private static final Logger LOG = Logger.getLogger("CortexDrone");

    private final File skillsDir;
    private final File disabledDir;

    SkillRetirement(File skillsDir, File disabledDir) {
        this.skillsDir = skillsDir;
        this.disabledDir = disabledDir;
    }

    /**
     * Moves {@code skills/<key>} to {@code skills-disabled/<key>}.
     *
     * @return false when there is no learned directory or the target exists
     */
    boolean retire(String key) {
        if (key == null || key.isEmpty()) return false;
        File source = findDirectory(this.skillsDir, key);
        if (source == null) return false;
        // Keep the existing directory name (it may differ in case or spacing)
        // so enabling restores exactly the same skill directory.
        File target = new File(this.disabledDir, source.getName());
        if (target.exists()) return false;
        if (!ensureDirectory(this.disabledDir)) return false;
        try {
            Files.move(source.toPath(), target.toPath());
            return true;
        } catch (Exception failure) {
            LOG.log(Level.WARNING, "Could not disable skill " + key, failure);
            return false;
        }
    }

    /**
     * Moves {@code skills-disabled/<key>} back into the active directory.
     *
     * @return the restored directory, or {@code null}
     */
    File restore(String key) {
        if (key == null || key.isEmpty()) return null;
        File source = findDirectory(this.disabledDir, key);
        if (source == null) return null;
        File target = new File(this.skillsDir, source.getName());
        if (target.exists()) return null;
        if (!ensureDirectory(this.skillsDir)) return null;
        try {
            Files.move(source.toPath(), target.toPath());
            return target;
        } catch (Exception failure) {
            LOG.log(Level.WARNING, "Could not enable skill " + key, failure);
            return null;
        }
    }

    /**
     * Names of retired skill directories, sorted.
     */
    List<String> names() {
        List<String> names = new ArrayList<>();
        File[] dirs = this.disabledDir != null ? this.disabledDir.listFiles(File::isDirectory) : null;
        if (dirs == null) return names;
        for (File dir : dirs) {
            if (new File(dir, SkillMarkdown.FILE_NAME).isFile()) names.add(dir.getName());
        }
        Collections.sort(names);
        return names;
    }

    private static boolean ensureDirectory(File directory) {
        return directory != null && (directory.isDirectory() || directory.mkdirs());
    }

    /**
     * Resolves a directory by its normalized name (case/separator insensitive),
     * so {@code my_farm} finds {@code skills/My Farm/}.
     */
    private static File findDirectory(File root, String key) {
        if (root == null) return null;
        File exact = new File(root, key);
        if (exact.isDirectory()) return exact;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return null;
        for (File dir : dirs) {
            if (SkillLibrary.normalize(dir.getName()).equals(key)) return dir;
        }
        return null;
    }
}
