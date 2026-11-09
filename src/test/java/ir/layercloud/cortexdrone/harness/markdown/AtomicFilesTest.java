package ir.layercloud.cortexdrone.harness.markdown;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * State files must survive a failed replace.
 *
 * <p>Regression: the stores deleted the target before renaming the temp file,
 * so a failed rename destroyed the previous content.</p>
 */
public class AtomicFilesTest {
    private static File tempDir() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-atomic").toFile();
        dir.deleteOnExit();
        return dir;
    }

    @Test
    public void writeReplacesExistingContent() throws Exception {
        File target = new File(tempDir(), "state.json");
        assertTrue(AtomicFiles.writeText(target, "first"));
        assertTrue(AtomicFiles.writeText(target, "second"));
        assertEquals("second", AtomicFiles.readText(target));
    }

    @Test
    public void noTempFileIsLeftBehind() throws Exception {
        File dir = tempDir();
        File target = new File(dir, "state.json");
        assertTrue(AtomicFiles.writeText(target, "content"));
        assertFalse("the temp file must be moved, not copied", new File(target.getPath() + ".tmp").exists());
    }

    @Test
    public void failedReplaceKeepsTheOldFile() throws Exception {
        File dir = tempDir();
        File target = new File(dir, "state.json");
        assertTrue(AtomicFiles.writeText(target, "original"));

        // A move from a nonexistent temp file cannot succeed; the target must
        // still hold the previous content.
        boolean replaced = AtomicFiles.replace(new File(dir, "missing.tmp"), target);
        assertFalse(replaced);
        assertEquals("original", AtomicFiles.readText(target));
    }

    @Test
    public void readMissingFileReturnsNull() throws Exception {
        assertNull(AtomicFiles.readText(new File(tempDir(), "nope.txt")));
        assertNull(AtomicFiles.readText(null));
    }

    @Test
    public void deleteRecursivelyRemovesATree() throws Exception {
        File dir = tempDir();
        File nested = new File(dir, "a/b");
        assertTrue(nested.mkdirs());
        Files.write(new File(nested, "f.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(AtomicFiles.deleteRecursively(dir));
        assertFalse(dir.exists());
        assertTrue("deleting a missing path is a no-op", AtomicFiles.deleteRecursively(dir));
    }

    @Test
    public void nullContentWritesAnEmptyFile() throws Exception {
        File target = new File(tempDir(), "empty.txt");
        assertTrue(AtomicFiles.writeText(target, null));
        assertEquals("", AtomicFiles.readText(target));
    }
}
