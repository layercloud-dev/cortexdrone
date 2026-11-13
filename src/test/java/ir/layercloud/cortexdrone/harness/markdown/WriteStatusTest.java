package ir.layercloud.cortexdrone.harness.markdown;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Persistence failures must be reported, not swallowed.
 *
 * <p>The stores used to log a warning and still return the saved entry, so a
 * tool reply said {@code saved:true} while the file was never written. The next
 * server start then silently lost the memory/lesson/skill.</p>
 */
public class WriteStatusTest {
    @Test
    public void aFailureIsReadAndClearedOnce() {
        WriteStatus.failed("memory could not be written to /tmp/x");
        assertTrue(WriteStatus.lastFailure().contains("could not be written"));
        assertEquals("memory could not be written to /tmp/x", WriteStatus.takeFailure());
        assertNull("a taken failure must not leak into the next call", WriteStatus.takeFailure());
    }

    @Test
    public void clearingResetsTheSlot() {
        WriteStatus.failed("boom");
        WriteStatus.clear();
        assertNull(WriteStatus.lastFailure());
    }

    /**
     * A real refusal: make the target directory read-only and try to write.
     * Windows ignores the read-only bit on a directory, so the check is skipped
     * there and the store-level tests cover the wiring instead.
     */
    @Test
    public void aReadOnlyDirectoryProducesAFailureReason() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-write-status").toFile();
        File target = new File(dir, "memory.json");

        boolean locked = dir.setWritable(false, false);
        try {
            if (!locked || dir.canWrite()) {
                // Root/Windows: cannot simulate the refusal here.
                assertTrue(true);
                return;
            }
            WriteStatus.clear();
            boolean written = AtomicFiles.writeText(target, "{}");
            assertTrue("the write must fail on a read-only directory", !written);
            WriteStatus.failed(WriteStatus.reason("memory", target.getAbsolutePath()));
            assertTrue(WriteStatus.takeFailure().contains("memory"));
        } finally {
            dir.setWritable(true, false);
        }
    }
}
