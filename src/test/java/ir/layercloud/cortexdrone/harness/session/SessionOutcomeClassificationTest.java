package ir.layercloud.cortexdrone.harness.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Outcome classification must not mis-file a real reply.
 *
 * <p>Regression source: {@code 20261113-072743-d5-l8-mining-1-f630} was filed
 * under {@code sessions/failure/} although the drone mined 60 ore, because the
 * reply contained "plus a few failed attempts".</p>
 */
public class SessionOutcomeClassificationTest {
    /** The exact reply text from that session, decoded from the transcript. */
    private static final String REAL_SUCCESSFUL_REPLY = ""
            + "I flew to the mine area, mined ores (including all the aluminium-bearing ore you "
            + "asked for) and then returned to the stash chest at (-219, 71, 597). The drone's "
            + "cargo is now full and the crate is still in its inventory.\n\n"
            + "**Result:**  \n"
            + "- Mined 60 ore blocks (plus a few failed attempts).  \n"
            + "- All cargo slots are occupied; the steel crate in slot 1 holds the loot.  \n"
            + "- No free inventory slots remain.\n\n"
            + "Let me know what you'd like to do next (e.g., unload the crate, continue mining).";

    @Test
    public void theMisFiledMiningReplyIsASuccess() {
        assertEquals(SessionOutcome.SUCCESS, SessionOutcome.fromReply(REAL_SUCCESSFUL_REPLY));
    }

    @Test
    public void anExplicitSignalWinsOverTheProse() {
        assertEquals(SessionOutcome.SUCCESS,
                SessionOutcome.fromReply("I couldn't find ore at first, but the task is done.\n"
                        + "OUTCOME: success"));
        assertEquals(SessionOutcome.FAILURE,
                SessionOutcome.fromReply("Everything looked fine.\nOUTCOME: failure"));
        assertEquals(SessionOutcome.FAILURE,
                SessionOutcome.fromReply("**OUTCOME: blocked** — the only shaft is sealed."));
        assertEquals(SessionOutcome.SUCCESS,
                SessionOutcome.fromReply("*outcome: done*"));
        assertEquals(SessionOutcome.INCOMPLETE,
                SessionOutcome.fromReply("OUTCOME: partial — 3 of 8 chests sorted"));
    }

    @Test
    public void explicitSignalParsingIsCaseAndMarkupTolerant() {
        assertEquals(SessionOutcome.SUCCESS, SessionOutcome.explicitOutcome("OUTCOME: SUCCESS"));
        assertEquals(SessionOutcome.FAILURE, SessionOutcome.explicitOutcome("outcome = failed"));
        assertNull(SessionOutcome.explicitOutcome("no signal here"));
        assertNull(SessionOutcome.explicitOutcome(null));
    }

    @Test
    public void aRealTaskFailureStillFilesAsFailure() {
        assertEquals(SessionOutcome.FAILURE,
                SessionOutcome.fromReply("I could not reach the farm: no route exists from the cave."));
        assertEquals(SessionOutcome.FAILURE,
                SessionOutcome.fromReply("The craft was impossible: not enough iron remained."));
        assertEquals(SessionOutcome.FAILURE,
                SessionOutcome.fromReply("Blocked by a protected zone at the base entrance."));
    }

    @Test
    public void aPastSetbackSentenceDoesNotDecideTheOutcome() {
        assertEquals(SessionOutcome.SUCCESS, SessionOutcome.fromReply(
                "After a few failed attempts I landed at the base and delivered 60 ore.\n"
                        + "All three crates are full."));
        assertEquals(SessionOutcome.SUCCESS, SessionOutcome.fromReply(
                "Initially the goto was blocked by stone, then the tunnel broke through and "
                        + "the wall is finished."));
    }

    @Test
    public void emptyRepliesStayIncomplete() {
        assertEquals(SessionOutcome.INCOMPLETE, SessionOutcome.fromReply(null));
        assertEquals(SessionOutcome.INCOMPLETE, SessionOutcome.fromReply("   \n\t "));
    }
}
