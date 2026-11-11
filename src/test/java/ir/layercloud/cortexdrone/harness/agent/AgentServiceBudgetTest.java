package ir.layercloud.cortexdrone.harness.agent;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/**
 * A thinking model can spend the whole completion budget on hidden reasoning
 * and return an empty message. The loop must widen the budget instead of ending
 * the task, and a cut-off answer must be continued instead of being treated as
 * a finished task.
 */
public class AgentServiceBudgetTest {
    private static LlmResponse truncated(String content) {
        return new LlmResponse(content, "reasoning that ran out",
                Collections.<ToolCall>emptyList(), "length", null);
    }

    @Test
    public void emptyTurnAtLeastDoublesTheBudget() {
        assertEquals(4096, AgentTurnRecovery.escalate(2048));
        assertEquals(8192, AgentTurnRecovery.escalate(4096));
        assertEquals(16384, AgentTurnRecovery.escalate(8192));
    }

    @Test
    public void tinyBudgetJumpsToTheMinimumRetryBudget() {
        assertEquals(4096, AgentTurnRecovery.escalate(1));
    }

    @Test
    public void budgetNeverExceedsTheCeiling() {
        assertEquals(16384, AgentTurnRecovery.escalate(16384));
        assertEquals(16384, AgentTurnRecovery.escalate(1_000_000));
    }

    @Test
    public void escalationIsMonotonic() {
        int previous = 1024;
        for (int i = 0; i < 8; i++) {
            int next = AgentTurnRecovery.escalate(previous);
            assertTrue("budget must not shrink", next >= previous);
            previous = next;
        }
        assertEquals(16384, previous);
    }

    @Test
    public void emptyReplyIsRetriedWithMoreBudget() {
        AgentTurnRecovery.Decision decision = AgentTurnRecovery.decide(
                truncated(""), 1, 2048, null);
        assertTrue(decision.retry);
        assertTrue(decision.empty);
        assertEquals(4096, decision.nextBudget);
        assertNotNull(decision.nudge);
    }

    @Test
    public void cutOffAnswerIsContinuedNotDelivered() {
        AgentTurnRecovery.Decision decision = AgentTurnRecovery.decide(
                truncated("I dug 12 blocks and"), 1, 8192, null);
        assertTrue(decision.retry);
        assertFalse(decision.empty);
        assertTrue(decision.nudge.contains("cut off"));
    }

    @Test
    public void cutOffAnswerIsDeliveredOnlyAfterTheRetriesRunOut() {
        AgentTurnRecovery.Decision decision = AgentTurnRecovery.decide(
                truncated("I dug 12 blocks and"), AgentTurnRecovery.MAX_RETRIES + 1, 16384, null);
        assertFalse(decision.retry);
        assertTrue(decision.deliverTruncated);
    }

    @Test
    public void aNormalFinalAnswerIsNotRetried() {
        LlmResponse response = new LlmResponse("Done: 20x20 room cleared.",
                "", Collections.<ToolCall>emptyList(), "stop", null);
        AgentTurnRecovery.Decision decision = AgentTurnRecovery.decide(response, 1, 8192, null);
        assertFalse(decision.retry);
        assertFalse(decision.deliverTruncated);
    }
}
