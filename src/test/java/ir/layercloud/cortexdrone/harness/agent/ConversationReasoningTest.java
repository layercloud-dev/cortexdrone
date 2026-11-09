package ir.layercloud.cortexdrone.harness.agent;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Thinking models reject a follow-up turn when the previous assistant message's
 * {@code reasoning_content} is missing ("The reasoning_content in the thinking
 * mode must be passed back to the API"). The conversation must replay it
 * verbatim, including on turns that carry tool calls.
 */
public class ConversationReasoningTest {
    private static Conversation conversation() {
        return new Conversation("system", "context", "task", 96_000, 8_000);
    }

    @Test
    public void assistantReplaysReasoningContentWithToolCalls() {
        Conversation conversation = conversation();
        conversation.assistant(new LlmResponse("", "weighing the options",
                Collections.singletonList(new ToolCall("call_1", "state", "{}")),
                "tool_calls", null));

        JsonObject message = conversation.messages().get(3);
        assertEquals("assistant", message.get("role").getAsString());
        assertEquals("weighing the options", message.get("reasoning_content").getAsString());
        assertNotNull(message.get("tool_calls"));
    }

    @Test
    public void assistantReplaysReasoningContentOnFinalAnswer() {
        Conversation conversation = conversation();
        conversation.assistant(new LlmResponse("done", "checked everything",
                Collections.emptyList(), "stop", null));

        JsonObject message = conversation.messages().get(3);
        assertEquals("checked everything", message.get("reasoning_content").getAsString());
        assertEquals("done", message.get("content").getAsString());
    }

    @Test
    public void assistantOmitsReasoningContentWhenModelDidNotThink() {
        Conversation conversation = conversation();
        conversation.assistant(new LlmResponse("done", "",
                Collections.emptyList(), "stop", null));

        assertFalse(conversation.messages().get(3).has("reasoning_content"));
    }

    /**
     * Regression: a thinking model that exhausted max_tokens returned only
     * reasoning. That empty turn used to be appended and replayed, so the
     * model resumed the same giant reasoning and the task died. It must not
     * enter the transcript at all.
     */
    @Test
    public void emptyAssistantTurnIsNotStored() {
        Conversation conversation = conversation();
        int before = conversation.messages().size();
        conversation.assistant(new LlmResponse("", "long truncated reasoning",
                Collections.emptyList(), "length", null));

        assertEquals(before, conversation.messages().size());
    }
}
