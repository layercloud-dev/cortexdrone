package ir.layercloud.cortexdrone.harness.markdown;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
    private static final java.util.concurrent.atomic.AtomicInteger TEMP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

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
        // One unique temp per write. A deterministic "<target>.tmp" let two
        // live store instances (a config reload creates new stores while an
        // old loop keeps writing through the previous instance) truncate and
        // move the same file, producing a spliced payload or a failed move.
        File temp = new File(target.getAbsolutePath() + "."
                + Long.toHexString(Thread.currentThread().getId()) + "-"
                + Integer.toHexString(TEMP_SEQUENCE.incrementAndGet()) + ".tmp");
        // UTF-8 explicitly: skills, lessons and memory are written by an LLM and
        // may contain non-ASCII text. FileWriter/FileReader use the platform
        // charset, which corrupted such files on a Windows server.
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp),
                StandardCharsets.UTF_8)) {
            writer.write(content != null ? content : "");
        } catch (Exception e) {
            // Never leave a half-written .tmp behind: on the next run it reads as
            // a real file to tools and confuses the user.
            if (temp.exists() && !temp.delete()) return false;
            return false;
        }
        if (replace(temp, target)) return true;
        // Windows can refuse the first move while another writer holds the
        // target open; one immediate retry covers that transient lock.
        if (replace(temp, target)) return true;
        if (temp.exists()) temp.delete();
        return false;
    }

    /**
     * Moves {@code temp} over {@code target}, falling back to a copy when the
     * filesystem refuses the rename (a running server holding the file open).
     */
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
                return copyOver(temp, target);
            }
        } catch (IOException e) {
            return copyOver(temp, target);
        }
    }

    private static boolean copyOver(File temp, File target) {
        try {
            Files.copy(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // A copy leaves the temp behind; the contract is "temp is consumed".
            if (!temp.delete()) temp.deleteOnExit();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads a whole text file, or returns {@code null} when it cannot be read.
     */
    public static String readText(File file) {
        if (file == null || !file.isFile()) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(file),
                StandardCharsets.UTF_8)) {
            StringBuilder out = new StringBuilder((int) Math.min(64_000L, file.length() + 32L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) out.append(buffer, 0, read);
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Deletes a file or directory tree; missing paths are a no-op.
     */
    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        return file.delete();
    }
}
