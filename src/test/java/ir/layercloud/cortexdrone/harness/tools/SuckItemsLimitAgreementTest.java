package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The {@code suck_items} schema and the action must share one radius policy.
 *
 * <p>Regression: the schema advertised a default of 4 while
 * {@code SuckItemsAction} fell back to 6.5, so the documented default was
 * never applied.</p>
 */
public class SuckItemsLimitAgreementTest {
    private static ToolSpec spec() {
        for (ToolSpec candidate : InventoryActionTools.specs()) {
            if ("suck_items".equals(candidate.getName())) return candidate;
        }
        fail("suck_items is not declared");
        return null;
    }

    private static double bound(String key) {
        return spec().getParameters().getAsJsonObject("properties")
                .getAsJsonObject("radius").get(key).getAsDouble();
    }

    @Test
    public void thePickupSchemaAdvertisesTheAppliedRadius() {
        assertEquals(InventoryLimits.MIN_SUCK_RADIUS, bound("minimum"), 0.0);
        assertEquals(InventoryLimits.MAX_SUCK_RADIUS, bound("maximum"), 0.0);
        assertEquals("the advertised default must be the applied default",
                InventoryLimits.DEFAULT_SUCK_RADIUS, bound("default"), 0.0);
    }
}
