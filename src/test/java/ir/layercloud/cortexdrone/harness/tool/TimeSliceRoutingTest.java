package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Time-slicing is decided by declared capabilities, not by the tool name.
 *
 * <p>A tool that declares {@link ToolCapability#TIME_SLICED} must become a
 * pollable job; a tool that does not must keep its own handler even with a large
 * radius. A spec that additionally declares
 * {@link ToolCapability#INLINE_WHEN_SMALL} stays inline while its radius is at
 * or below the threshold, so a cheap scan does not pay for a poll round-trip.</p>
 */
public class TimeSliceRoutingTest {
    private static ToolSpec.Builder base(String name) {
        return ToolSpec.builder(name)
                .description("test")
                .parameters(ToolSchema.object().optionalInt("radius", "r", 1, 200, 24))
                .affinity(ToolAffinity.ASYNC);
    }

    private static ToolSpec spec(String name, boolean timeSliced) {
        ToolSpec.Builder builder = base(name);
        if (timeSliced) builder.capability(ToolCapability.TIME_SLICED);
        return builder.build();
    }

    private static ToolSpec inlineSmallScan() {
        return base("scan_blocks")
                .capabilities(ToolCapability.TIME_SLICED, ToolCapability.INLINE_WHEN_SMALL)
                .build();
    }

    private static JsonObject radius(int value) {
        JsonObject args = new JsonObject();
        args.addProperty("radius", value);
        return args;
    }

    @Test
    public void aDeclaredTimeSlicedToolIsRoutedToTheQueue() {
        assertTrue(ToolInvoker.isTimeSliced(spec("future_scan", true), radius(1)));
    }

    @Test
    public void anUndeclaredToolKeepsItsOwnHandler() {
        assertFalse(ToolInvoker.isTimeSliced(spec("get_entities", false), radius(180)));
        assertFalse(ToolInvoker.isTimeSliced(spec("scan_chests", false), radius(64)));
    }

    @Test
    public void aCheapBlockScanStaysInline() {
        assertFalse(ToolInvoker.isTimeSliced(inlineSmallScan(), radius(24)));
        assertTrue(ToolInvoker.isTimeSliced(inlineSmallScan(), radius(64)));
    }

    @Test
    public void aMissingSpecIsNotTimeSliced() {
        assertFalse(ToolInvoker.isTimeSliced(null, radius(64)));
    }
}
