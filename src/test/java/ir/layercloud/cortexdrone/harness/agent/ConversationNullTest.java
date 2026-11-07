package ir.layercloud.cortexdrone.harness.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A tool result containing a JSON null used to crash the whole task loop:
 * {@code Conversation.bound} called {@code getAsJsonObject()} on JsonNull and
 * threw "Not a JSON Object: null". Tool results legitimately contain nulls.
 */
public class ConversationNullTest {
    private static Conversation conversation() {
        return new Conversation("system", "context", "task", 96_000, 8_000);
    }

    @Test
    public void toolResultContainingNullDoesNotThrow() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("value", JsonNull.INSTANCE);
        JsonObject nested = new JsonObject();
        nested.add("inner", JsonNull.INSTANCE);
        result.add("details", nested);
        result.addProperty("after", "kept");

        Conversation conversation = conversation();
        conversation.toolResult(new ToolCall("call_1", "state", "{}"), result);

        JsonObject content = new JsonParser().parse(
                conversation.messages().get(3).get("content").getAsString()).getAsJsonObject();
        assertTrue(content.get("ok").getAsBoolean());
        assertEquals("kept", content.get("after").getAsString());
    }

    @Test
    public void deeplyNestedNullDoesNotThrow() {
        JsonObject result = new JsonObject();
        JsonObject level = result;
        for (int i = 0; i < 8; i++) {
            JsonObject child = new JsonObject();
            child.add("null_field", JsonNull.INSTANCE);
            child.addProperty("depth", i);
            level.add("level" + i, child);
            level = child;
        }
        Conversation conversation = conversation();
        conversation.toolResult(new ToolCall("call_2", "get_drops", "{}"), result);
        assertNotNull(conversation.messages().get(3).get("content"));
    }

    @Test
    public void oversizedArrayResultIsCappedNotCrashed() {
        JsonObject result = new JsonObject();
        result.add("nothing", JsonNull.INSTANCE);
        JsonArray rows = new JsonArray();
        for (int i = 0; i < 600; i++) {
            JsonObject row = new JsonObject();
            row.addProperty("i", i);
            row.addProperty("name", "minecraft:some_long_block_name_" + i);
            row.addProperty("note", "padding for size");
            rows.add(row);
        }
        result.add("rows", rows);

        Conversation conversation = conversation();
        conversation.toolResult(new ToolCall("call_3", "scan_blocks", "{}"), result);

        JsonObject content = new JsonParser().parse(
                conversation.messages().get(3).get("content").getAsString()).getAsJsonObject();
        // Either the whole result was replaced by the truncation envelope, or
        // the long array was shortened in place. Both are bounded, neither throws.
        assertTrue(content.has("truncated") || content.has("rows"));
        assertTrue("content must stay bounded",
                content.toString().length() < 20_000);
    }
}
