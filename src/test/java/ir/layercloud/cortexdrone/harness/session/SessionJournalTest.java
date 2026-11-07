package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SessionJournalTest {
    private static SessionJournal journal(File root) {
        return new SessionJournal(root, null);
    }

    private static SessionJournal.SessionRun begin(SessionJournal journal, String prompt) {
        return journal.begin(7, "drone1", 1, "steve", "steve", "NEW_TASK",
                "test-model", 0, 10.5D, 64.0D, -3.25D, prompt);
    }

    private static int filesIn(File directory, String extension) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(extension));
        return files != null ? files.length : 0;
    }

    @Test
    public void successWritesJsonMarkdownAndIndex() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-sessions-success").toFile();
        SessionJournal journal = journal(root);
        SessionJournal.SessionRun run = begin(journal, "mine 10 iron ore near the base");
        run.thought(1, "plan: scan for iron then dig");
        run.tool(1, "scan_blocks", "{\"block\":\"iron_ore\"}", true, "{\"ok\":true,\"count\":10}");
        run.finish(SessionOutcome.SUCCESS, "final_answer", "Done, mined 10 iron ore.");

        File success = new File(root, "sessions/success");
        assertEquals(1, filesIn(success, ".json"));
        assertEquals(1, filesIn(success, ".md"));
        assertEquals(0, journal.activeCount());

        List<JsonObject> recent = journal.recent(5, null);
        assertEquals(1, recent.size());
        assertEquals("success", recent.get(0).get("outcome").getAsString());
        assertEquals("mining", recent.get(0).get("topic").getAsString());
        assertEquals(run.id(), recent.get(0).get("id").getAsString());

        Map<String, Integer> counts = journal.counts();
        assertEquals(Integer.valueOf(1), counts.get("success"));
        assertEquals(Integer.valueOf(0), counts.get("failure"));
    }

    @Test
    public void failureAndCancelledHaveTheirOwnFolders() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-sessions-failure").toFile();
        SessionJournal journal = journal(root);

        begin(journal, "go to the iron farm").finish(SessionOutcome.FAILURE,
                "final_answer", "I couldn't find a safe route to the farm.");
        SessionJournal.SessionRun cancelled = begin(journal, "bring me dirt");
        cancelled.cancelRequested("model_cancel");
        cancelled.finish(SessionOutcome.SUCCESS, "final_answer", "Task cancelled.");

        assertEquals(1, filesIn(new File(root, "sessions/failure"), ".json"));
        assertEquals(1, filesIn(new File(root, "sessions/cancelled"), ".json"));
        assertEquals(0, filesIn(new File(root, "sessions/success"), ".json"));
    }

    @Test
    public void closeActiveWritesInterruptedRunsOnce() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-sessions-interrupted").toFile();
        SessionJournal journal = journal(root);
        SessionJournal.SessionRun first = begin(journal, "build a wall");
        SessionJournal.SessionRun second = begin(journal, "mine stone");
        first.tool(1, "place_block", "{}", true, "{}");
        second.tool(1, "goto", "{}", true, "{}");
        assertEquals(2, journal.activeCount());

        journal.closeActive(SessionOutcome.INTERRUPTED, "server_stopping");
        assertEquals(0, journal.activeCount());
        assertEquals(2, filesIn(new File(root, "sessions/interrupted"), ".json"));

        // A late loop exit must not write a second copy.
        first.finish(SessionOutcome.SUCCESS, "final_answer", "late");
        assertEquals(2, filesIn(new File(root, "sessions/interrupted"), ".json"));
        assertEquals(0, filesIn(new File(root, "sessions/success"), ".json"));
    }

    @Test
    public void findWorksByFullIdAndPrefix() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-sessions-find").toFile();
        SessionJournal journal = journal(root);
        SessionJournal.SessionRun run = begin(journal, "store items");
        run.finish(SessionOutcome.SUCCESS, "final_answer", "Stored everything.");

        assertNotNull(journal.find(run.id()));
        assertNotNull(journal.find(run.id().substring(0, 12)));
        assertNull(journal.find("does-not-exist"));
    }

    @Test
    public void replyClassificationSeparatesSuccessFromFailure() {
        assertEquals(SessionOutcome.SUCCESS,
                SessionOutcome.fromReply("Done, placed 10 blocks and returned home."));
        assertEquals(SessionOutcome.FAILURE,
                SessionOutcome.fromReply("I couldn't reach the farm; no safe route was found."));
        assertEquals(SessionOutcome.INCOMPLETE, SessionOutcome.fromReply("   "));
    }
}
