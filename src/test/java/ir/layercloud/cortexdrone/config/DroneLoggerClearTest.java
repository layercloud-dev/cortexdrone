package ir.layercloud.cortexdrone.config;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The debug log is batched; a clear must also drop buffered lines.
 *
 * <p>Regression: {@code /drone debug clear} truncated the file and the
 * in-memory ring, but the next batched flush (600 ms later) rewrote every line
 * that had been logged before the clear, so the file was never actually
 * empty.</p>
 */
public class DroneLoggerClearTest {
    @Test
    public void clearingDropsLinesThatWereNotFlushedYet() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-log-clear").toFile();
        File log = new File(dir, "cortexdrone.log");

        DroneLogger logger = new DroneLogger();
        logger.setDebugLogFile(log);
        logger.log("TEST", null, "line written before the clear", "detail=1");
        logger.clearDebugLog();

        // The batched flush sleeps 600 ms before writing; wait past it.
        Thread.sleep(1_600L);

        String text = log.isFile()
                ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8)
                : "";
        assertFalse("a cleared log must not be repopulated by the pending flush: " + text,
                text.contains("line written before the clear"));
        assertTrue("the debug ring buffer is cleared too",
                logger.getRecentDebugEntries(10).isEmpty());
    }

    @Test
    public void linesLoggedAfterAClearAreStillWritten() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-log-after-clear").toFile();
        File log = new File(dir, "cortexdrone.log");

        DroneLogger logger = new DroneLogger();
        logger.setDebugLogFile(log);
        logger.log("TEST", null, "old line", "");
        logger.clearDebugLog();
        logger.log("TEST", null, "fresh line", "");

        Thread.sleep(1_600L);

        String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        assertTrue("a clear must not stop later logging: " + text, text.contains("fresh line"));
        assertFalse("the pre-clear line must stay gone: " + text, text.contains("old line"));
    }
}
