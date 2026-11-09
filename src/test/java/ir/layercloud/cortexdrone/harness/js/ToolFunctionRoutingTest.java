package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolCapability;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Auto-polling is decided by the declared capability, not the tool name.
 *
 * <p>Regression: only {@code scan_blocks} was auto-polled because the function
 * string-matched its name; any future {@link ToolCapability#TIME_SLICED} tool
 * would have returned a pending job to the script.</p>
 */
public class ToolFunctionRoutingTest {
    private static ToolSpec spec(String name, boolean timeSliced) {
        ToolSpec.Builder builder = ToolSpec.builder(name)
                .description("test")
                .parameters(ToolSchema.object())
                .affinity(ToolAffinity.ASYNC);
        if (timeSliced) builder.capability(ToolCapability.TIME_SLICED);
        return builder.build();
    }

    private static JsonObject pending(boolean done) {
        JsonObject result = new JsonObject();
        result.addProperty("job_id", "job-1");
        result.addProperty("pending", !done);
        result.addProperty("done", done);
        return result;
    }

    @Test
    public void aTimeSlicedToolIsPolledByCapability() {
        assertTrue(DroneToolFunction.shouldAutoPoll(spec("future_scan", true), pending(false)));
    }

    @Test
    public void aNonTimeSlicedToolIsNotPolled() {
        assertFalse(DroneToolFunction.shouldAutoPoll(spec("get_entities", false), pending(false)));
    }

    @Test
    public void aFinishedResultIsNotPolled() {
        assertFalse(DroneToolFunction.shouldAutoPoll(spec("scan_blocks", true), pending(true)));
    }
}
