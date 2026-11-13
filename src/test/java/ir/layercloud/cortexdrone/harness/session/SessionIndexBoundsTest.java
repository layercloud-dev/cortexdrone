package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The session index is a bounded cache, not an append-only log.
 *
 * <p>It kept one line per task forever; every {@code /drone sessions list} then
 * parsed the whole file. The per-session JSON transcripts stay authoritative.</p>
 */
public class SessionIndexBoundsTest {
    @Test
    public void theIndexIsTrimmedToTheDocumentedLineCap() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-index-bounds").toFile();
        SessionJournal journal = new SessionJournal(root, null);

        int total = SessionIndex.MAX_LINES + 200;
        for (int i = 0; i < total; i++) {
            SessionJournal.SessionRun run = journal.begin(i, "drone1", 1, "steve", "steve",
                    "NEW_TASK", "test-model", 0, 0, 64, 0, "task " + i);
            run.finish(SessionOutcome.SUCCESS, "final_answer", "done " + i);
        }

        File index = new File(root, "sessions/index.jsonl");
        assertTrue(index.isFile());
        int lines = 0;
        for (String line : Files.readAllLines(index.toPath())) {
            if (!line.trim().isEmpty()) lines++;
        }
        assertTrue("index must stay bounded, was " + lines,
                lines <= SessionIndex.MAX_LINES + SessionIndex.TRIM_CHECK_INTERVAL);
        assertTrue("the newest entries must survive", lines > SessionIndex.MAX_LINES - 300);

        // The newest run is still readable and the per-run files are untouched.
        JsonObject newest = journal.recent(1, null).get(0);
        assertEquals("task " + (total - 1), newest.get("prompt").getAsString());
        assertEquals("success", newest.get("outcome").getAsString());
    }
}
