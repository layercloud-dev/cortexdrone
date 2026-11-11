package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Only a spec that opts into inline-small scans may use the time-sliced queue.
 *
 * <p>Regression: the router matched {@code radius > 32} without checking which
 * tool was called, so {@code get_entities}, {@code scan_chests} or
 * {@code scan_furnaces} with a large radius were silently converted into an
 * ore scan (the job's default filter) and returned the wrong data.</p>
 *
 * <p>The guard is now the declared {@link ToolCapability#INLINE_WHEN_SMALL}
 * capability, not a tool-name string.</p>
 */
public class LargeScanRoutingTest {
    private static JsonObject radius(int value) {
        JsonObject args = new JsonObject();
        args.addProperty("radius", value);
        return args;
    }

    private static ToolSpec blockScan() {
        return ToolSpec.builder("scan_blocks")
                .description("test")
                .parameters(ToolSchema.object().optionalInt("radius", "r", 1, 200, 24))
                .affinity(ToolAffinity.ASYNC)
                .capabilities(ToolCapability.TIME_SLICED, ToolCapability.INLINE_WHEN_SMALL)
                .build();
    }

    private static ToolSpec plainScan(String name) {
        return ToolSpec.builder(name)
                .description("test")
                .parameters(ToolSchema.object().optionalInt("radius", "r", 1, 200, 24))
                .affinity(ToolAffinity.ASYNC)
                .capability(ToolCapability.TIME_SLICED)
                .build();
    }

    @Test
    public void blockScanAboveTheThresholdIsTimeSliced() {
        assertTrue(ToolInvoker.shouldTimeSlice(blockScan(), radius(33)));
        assertTrue(ToolInvoker.shouldTimeSlice(blockScan(), radius(64)));
    }

    @Test
    public void blockScanBelowTheThresholdRunsInline() {
        assertFalse(ToolInvoker.shouldTimeSlice(blockScan(), radius(24)));
        assertFalse(ToolInvoker.shouldTimeSlice(blockScan(), radius(32)));
        assertFalse(ToolInvoker.shouldTimeSlice(blockScan(), new JsonObject()));
    }

    @Test
    public void otherToolsAreNeverTimeSlicedIntoABlockScan() {
        for (String tool : new String[]{"get_entities", "scan_chests", "scan_furnaces",
                "find_item", "scan_machines", "get_player"}) {
            assertFalse(tool + " must keep its own handler",
                    ToolInvoker.shouldTimeSlice(plainScan(tool), radius(120)));
        }
    }

    @Test
    public void nullInputsAreSafe() {
        assertFalse(ToolInvoker.shouldTimeSlice(null, radius(64)));
        assertFalse(ToolInvoker.shouldTimeSlice(blockScan(), null));
    }
}
