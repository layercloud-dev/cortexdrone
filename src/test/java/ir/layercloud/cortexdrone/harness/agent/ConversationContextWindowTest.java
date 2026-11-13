package ir.layercloud.cortexdrone.harness.agent;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A gateway context-window rejection must shrink the transcript, not kill the
 * task.
 *
 * <p>Rotated-log evidence: {@code HTTP 400 ... "request exceeds the available
 * context window for this model"} ended a run even though compaction could have
 * fixed it. The gateway counts tokens, so the local character estimate can look
 * healthy while the request is still too large.</p>
 */
public class ConversationContextWindowTest {
    private static Conversation conversation(int turns) {
        Conversation conversation = new Conversation("system prompt", "live context", "task", 80_000, 8_000);
        for (int i = 0; i < turns; i++) {
            JsonObject call = new JsonObject();
            call.addProperty("role", "assistant");
            call.addProperty("content", "turn " + i + " " + repeat('x', 400));
            conversation.messages().add(call);
            JsonObject tool = new JsonObject();
            tool.addProperty("role", "tool");
            tool.addProperty("content", repeat('y', 600));
            conversation.messages().add(tool);
        }
        return conversation;
    }

    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }

    @Test
    public void aContextRejectionMessageIsRecognised() {
        assertTrue(LlmClient.isContextWindowRejection(new RuntimeException(
                "LLM gateway HTTP 400 - {\"error\":{\"message\":\"request exceeds the available "
                        + "context window for this model\"}}")));
        assertTrue(LlmClient.isContextWindowRejection(new RuntimeException("context length exceeded")));
        assertFalse(LlmClient.isContextWindowRejection(new RuntimeException("HTTP 401 bad key")));
        assertFalse(LlmClient.isContextWindowRejection(null));
    }

    @Test
    public void aggressiveCompactionShrinksEvenWhenTheEstimateLooksFine() {
        Conversation conversation = conversation(30);
        int before = conversation.estimate();
        assertFalse("the local estimate deliberately stays under the cap",
                conversation.estimateTooLarge());

        assertTrue(conversation.compactAggressively(6));
        int after = conversation.estimate();
        assertTrue("compaction must actually shrink: before=" + before + " after=" + after,
                after < before);

        // The task, the live context and the newest turns survive.
        assertEquals("system", conversation.messages().get(0).get("role").getAsString());
        assertEquals("live context",
                conversation.messages().get(1).get("content").getAsString());
        assertEquals("task", conversation.messages().get(2).get("content").getAsString());
        assertEquals(1, conversation.compactions());
    }

    @Test
    public void aggressiveCompactionLeavesNoOrphanToolHead() {
        Conversation conversation = conversation(20);
        conversation.compactAggressively(4);
        String firstKept = conversation.messages().get(3).get("role").getAsString();
        assertTrue("the digest must be a system message", "system".equals(firstKept));
        String afterDigest = conversation.messages().get(4).get("role").getAsString();
        assertFalse("a tool result must never open the retained window", "tool".equals(afterDigest));
    }

    @Test
    public void normalCompactionStillWorks() {
        Conversation conversation = new Conversation("system prompt", "live context", "task", 80_000, 8_000);
        for (int i = 0; i < 40; i++) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", repeat('z', 2_000));
            conversation.messages().add(message);
        }
        assertTrue(conversation.estimateTooLarge());
        conversation.compactIfNeeded();
        assertFalse(conversation.estimateTooLarge());
        assertEquals(1, conversation.compactions());
    }
}
