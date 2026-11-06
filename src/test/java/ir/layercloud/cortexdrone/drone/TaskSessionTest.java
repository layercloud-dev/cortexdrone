package ir.layercloud.cortexdrone.drone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TaskSessionTest {
    @Test
    public void beginAndCompleteOneSession() {
        TaskSession session = new TaskSession();
        assertFalse(session.isActive());
        session.begin(7, "mine 8 iron");
        assertTrue(session.isActive());
        assertEquals(7, session.getLoopId());
        assertEquals("mine 8 iron", session.getPrompt());
        session.complete();
        assertFalse(session.isActive());
    }

    @Test
    public void notesAttachToRunningTaskWithoutQueueingInstructions() {
        TaskSession session = new TaskSession();
        session.begin(1, "build a wall");
        session.addNote("by the way, there is a chest nearby");
        session.addNote("btw keep away from lava");
        assertTrue(session.isActive());
        assertTrue(session.getSummary().contains("chest"));
        assertTrue(session.getSummary().contains("lava"));
        assertEquals(2, session.getNotes().size());
    }

    @Test
    public void cancelClearsNotesAndDeactivates() {
        TaskSession session = new TaskSession();
        session.begin(2, "mine stone");
        session.addNote("side note");
        session.cancel();
        assertFalse(session.isActive());
        assertEquals(0, session.getNotes().size());
    }
}
