package ir.layercloud.cortexdrone.harness.lesson;

import ir.layercloud.cortexdrone.harness.markdown.AtomicFiles;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * On-disk layout for learned lessons.
 *
 * <p>Extracted from {@code LessonStore}. Each lesson is one directory holding a
 * single {@code LESSON.md}; the directory name is derived from scope, title and
 * id so it is readable and stable. Writes are atomic, so a crash cannot leave a
 * half-written lesson behind.</p>
 */
@SuppressWarnings("unused")
final class LessonFiles {
    /**
     * Longest directory-name segment derived from a title or scope.
     */
    private static final int MAX_SLUG = 48;

    private final File lessonsDir;

    LessonFiles(File lessonsDir) {
        this.lessonsDir = lessonsDir;
    }

    /**
     * The directory a lesson belongs in.
     */
    File directoryFor(Lesson lesson) {
        if (lesson.getPath() != null && !lesson.getPath().trim().isEmpty()) {
            return new File(lesson.getPath());
        }
        String scope = slug(lesson.getScope(), "general");
        String title = slug(lesson.getTitle(), "lesson");
        String id = lesson.getId() != null
                ? Integer.toHexString(lesson.getId().hashCode()) : "lesson";
        return new File(this.lessonsDir, scope + "-" + title + "-" + id);
    }

    /**
     * Writes the lesson's markdown; returns false when the directory is unusable.
     */
    boolean persist(Lesson lesson) {
        File directory = directoryFor(lesson);
        if (!directory.isDirectory() && !directory.mkdirs()) return false;
        lesson.setPath(directory.getAbsolutePath());
        return AtomicFiles.writeText(new File(directory, LessonMarkdown.FILE_NAME),
                LessonMarkdown.write(lesson));
    }

    /**
     * Removes the lesson's directory.
     */
    boolean delete(Lesson lesson) {
        if (lesson == null) return false;
        return AtomicFiles.deleteRecursively(directoryFor(lesson));
    }

    /**
     * Reads the markdown of one lesson directory, or {@code null}.
     */
    String read(Lesson lesson) {
        return AtomicFiles.readText(new File(directoryFor(lesson), LessonMarkdown.FILE_NAME));
    }

    /**
     * Every lesson directory, sorted by name for a stable load order.
     */
    File[] directories() {
        if (this.lessonsDir == null) return new File[0];
        File[] found = this.lessonsDir.listFiles(File::isDirectory);
        if (found == null) return new File[0];
        Arrays.sort(found, Comparator.comparing(File::getName));
        return found;
    }

    /**
     * Lowercase, dash-separated, bounded; falls back when nothing survives.
     */
    static String slug(String value, String fallback) {
        String clean = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        clean = clean.replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (clean.isEmpty()) clean = fallback;
        return clean.length() > MAX_SLUG ? clean.substring(0, MAX_SLUG) : clean;
    }
}
