package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolCapability;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The question tool must stay asynchronous: a synchronous (MAIN) handler would
 * wait on the server thread and the answer packet could never be processed.
 */
public class QuestionToolsTest {
    private static ToolSpec spec() {
        for (ToolSpec candidate : QuestionTools.specs()) {
            if ("ask_player".equals(candidate.getName())) return candidate;
        }
        fail("ask_player is not declared");
        return null;
    }

    @Test
    public void askPlayerIsAsyncAndPresentationOnly() {
        ToolSpec spec = spec();
        assertEquals(ToolAffinity.ASYNC, spec.getAffinity());
        assertTrue(spec.has(ToolCapability.PRESENTATION));
        assertFalse("asking must not be classified as a world mutation", spec.mutatesAnything());
    }

    @Test
    public void questionIsRequiredAndBounded() {
        ToolSpec spec = spec();
        JsonArray required = spec.getParameters().getAsJsonArray("required");
        assertNotNull(required);
        assertEquals(1, required.size());
        assertEquals("question", required.get(0).getAsString());

        String description = spec.getDescription();
        assertTrue(description.contains(String.valueOf(QuestionLimits.MAX_OPTIONS)));
        assertTrue(description.contains("timeout_ms"));
    }

    @Test
    public void optionsAndTimeoutsAdvertiseTheirBounds() {
        ToolSpec spec = spec();
        com.google.gson.JsonObject properties =
                spec.getParameters().getAsJsonObject("properties");

        com.google.gson.JsonObject timeout = properties.getAsJsonObject("timeout_ms");
        assertEquals(QuestionLimits.MIN_TIMEOUT_MS, timeout.get("minimum").getAsInt());
        assertEquals(QuestionLimits.MAX_TIMEOUT_MS, timeout.get("maximum").getAsInt());
        assertEquals(QuestionLimits.DEFAULT_TIMEOUT_MS, timeout.get("default").getAsInt());

        assertTrue(properties.has("options"));
        assertTrue(properties.has("allow_free_text"));
        assertTrue(properties.has("player"));
    }
}
