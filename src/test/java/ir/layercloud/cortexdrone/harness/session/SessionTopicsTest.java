package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Session topic classification is a small, testable vocabulary.
 */
public class SessionTopicsTest {
    @Test
    public void promptsMapToTheirTopic() {
        assertEquals("mining", SessionTopics.classify("mine 32 iron ore", null));
        assertEquals("building", SessionTopics.classify("build a small house here", null));
        assertEquals("navigation", SessionTopics.classify("go to the base", null));
        assertEquals("inventory", SessionTopics.classify("put the items in the chest", null));
        assertEquals("combat", SessionTopics.classify("kill the zombie", null));
        assertEquals("survival", SessionTopics.classify("escape the lava", null));
    }

    @Test
    public void vaguePromptsFallBackToTheToolsUsed() {
        List<JsonObject> transcript = new ArrayList<>();
        JsonObject call = new JsonObject();
        call.addProperty("type", "tool");
        call.addProperty("name", "break_block");
        transcript.add(call);
        assertEquals("mining", SessionTopics.classify("salam", transcript));
    }

    @Test
    public void unknownInputIsGeneral() {
        assertEquals("general", SessionTopics.classify("hello there", null));
        assertEquals("general", SessionTopics.classify(null, null));
        assertEquals("general", SessionTopics.classify("", new ArrayList<>()));
    }

    @Test
    public void knownTopicsCoverEveryRule() {
        List<String> known = SessionTopics.known();
        assertTrue(known.contains("general"));
        assertEquals(7, known.size());
    }
}
