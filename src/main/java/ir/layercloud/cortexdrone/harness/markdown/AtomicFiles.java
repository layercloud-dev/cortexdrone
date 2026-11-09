package ir.layercloud.cortexdrone.harness.markdown;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.io.FileWriter;
import java.io.FileReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe text file writes for the harness stores.
 *
 * <p>The previous pattern was: write {@code .tmp}, {@code delete()} the target,
 * then {@code renameTo(target)}. If the rename failed (permissions, a lock, a
 * different filesystem) the old file was already gone and the new content was
 * stranded in {@code .tmp} — silent data loss for skills, lessons and memory.
 * A single {@code Files.move(..., REPLACE_EXISTING)} keeps the old file until
 * the new one is in place.</p>
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /**
     * Writes {@code content} to {@code target}, replacing it atomically when
     * the filesystem supports it.
     *
     * @return true when the file now holds the new content
     */
    public static boolean writeText(File target, String content) {
        if (target == null) return false;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        File temp = new File(target.getAbsolutePath() + ".tmp");
        try (Writer writer = new FileWriter(temp)) {
            writer.write(content != null ? content : "");
        } catch (Exception e) {
            return false;
        }
        return replace(temp, target);
    }

    /** Moves {@code temp} over {@code target}, falling back to a plain replace. */
    public static boolean replace(File temp, File target) {
        if (temp == null || target == null || !temp.isFile()) return false;
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException notAtomic) {
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** Reads a whole text file, or returns {@code null} when it cannot be read. */
    public static String readText(File file) {
        if (file == null || !file.isFile()) return null;
        try (Reader reader = new FileReader(file)) {
            StringBuilder out = new StringBuilder((int) Math.min(64_000L, file.length() + 32L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) out.append(buffer, 0, read);
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes a file or directory tree; missing paths are a no-op. */
    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        return file.delete();
    }
}
