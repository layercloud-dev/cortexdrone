package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A bundled skill runs through its bound function name, not just skills.call.
 *
 * <p>Harness lives in {@link SkillTestSupport}; this suite keeps one behavioural area
 * per file.</p>
 */
public class SkillBehaviorTest extends SkillTestSupport {
    @Test
    public void realBundledSkillRunsThroughItsFunctionName() throws Exception {
        Harness harness = new Harness(library(), state(), null);
        // escape_cave is a real bundled skill; its 2x2 shaft logic runs from the
        // function call exactly as it does from skills.call.
        JsonObject viaCall = harness.run("escape_cave", "{x:0,z:0}");
        assertTrue("bundled skill must run via skills.call: " + viaCall,
                viaCall.get("ok").getAsBoolean());

        JsonObject direct = harness.runAsFunction("escape_cave", "{x:0,z:0,top_y:66}");
        assertTrue("bundled skill must run as skills.escape_cave(...): " + direct,
                direct.get("ok").getAsBoolean());
        assertEquals("the shaft must stop at the requested top", 66,
                direct.get("surfaceY").getAsInt());
    }
}
