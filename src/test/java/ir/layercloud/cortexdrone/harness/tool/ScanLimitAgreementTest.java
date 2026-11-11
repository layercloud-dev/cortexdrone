package ir.layercloud.cortexdrone.harness.tool;

import ir.layercloud.cortexdrone.harness.tools.WorldTools;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Scan size limits have exactly one source, and the schema publishes it.
 *
 * <p>Regression: the tool schema promised radius 64 / limit 2000 while the job
 * silently capped them at 32 / 400, and reported the already-capped value as
 * {@code capped_radius}, so a caller could not tell a cap from a request.</p>
 *
 * <p>These assertions read the <em>declared schema JSON</em> and compare it to
 * the enforced table. An earlier version compared the table to aliases of
 * itself, which could never fail.</p>
 */
public class ScanLimitAgreementTest {
    private static int schemaBound(String tool, String field, String key) {
        for (ToolSpec spec : WorldTools.specs()) {
            if (!tool.equals(spec.getName())) continue;
            return spec.getParameters().getAsJsonObject("properties")
                    .getAsJsonObject(field).get(key).getAsInt();
        }
        fail("no spec named " + tool);
        return -1;
    }

    @Test
    public void theScanSchemaAdvertisesTheEnforcedRadius() {
        assertEquals("scan_blocks minimum",
                ScanLimits.MIN_RADIUS, schemaBound("scan_blocks", "radius", "minimum"));
        assertEquals("scan_blocks maximum",
                ScanLimits.MAX_RADIUS, schemaBound("scan_blocks", "radius", "maximum"));
        assertEquals("scan_blocks default radius",
                ScanLimits.DEFAULT_RADIUS, schemaBound("scan_blocks", "radius", "default"));
    }

    @Test
    public void theScanSchemaAdvertisesTheEnforcedResultLimit() {
        assertEquals("scan_blocks limit minimum",
                ScanLimits.MIN_RESULTS, schemaBound("scan_blocks", "limit", "minimum"));
        assertEquals("scan_blocks limit maximum",
                ScanLimits.MAX_RESULTS, schemaBound("scan_blocks", "limit", "maximum"));
        assertEquals("scan_blocks default limit",
                ScanLimits.DEFAULT_RESULTS, schemaBound("scan_blocks", "limit", "default"));
    }

    @Test
    public void theVeinAndDistantSchemasAdvertiseTheEnforcedBounds() {
        assertEquals(ScanLimits.MIN_VEIN_RADIUS,
                schemaBound("scan_veins", "radius", "minimum"));
        assertEquals(ScanLimits.MAX_VEIN_RADIUS,
                schemaBound("scan_veins", "radius", "maximum"));
        assertEquals(ScanLimits.DEFAULT_VEIN_RADIUS,
                schemaBound("scan_veins", "radius", "default"));

        assertEquals(ScanLimits.MIN_DISTANT_RADIUS,
                schemaBound("find_distant", "max_radius", "minimum"));
        assertEquals(ScanLimits.MAX_DISTANT_RADIUS,
                schemaBound("find_distant", "max_radius", "maximum"));
        assertEquals(ScanLimits.DEFAULT_DISTANT_RADIUS,
                schemaBound("find_distant", "max_radius", "default"));
    }

    @Test
    public void theEntityAndDropSchemasAdvertiseTheEnforcedBounds() {
        assertEquals(ScanLimits.MIN_ENTITY_RADIUS,
                schemaBound("get_entities", "radius", "minimum"));
        assertEquals(ScanLimits.MAX_ENTITY_RADIUS,
                schemaBound("get_entities", "radius", "maximum"));
        assertEquals(ScanLimits.DEFAULT_ENTITY_RADIUS,
                schemaBound("get_entities", "radius", "default"));

        assertEquals(ScanLimits.MIN_DROP_RADIUS,
                schemaBound("get_drops", "radius", "minimum"));
        assertEquals(ScanLimits.MAX_DROP_RADIUS,
                schemaBound("get_drops", "radius", "maximum"));
        assertEquals(ScanLimits.DEFAULT_DROP_RADIUS,
                schemaBound("get_drops", "radius", "default"));
    }

    @Test
    public void theSlicerUsesTheSameTable() {
        assertEquals(ScanLimits.MAX_RADIUS, ScanJobs.ABSOLUTE_RADIUS_CAP);
        assertEquals(ScanLimits.MAX_RESULTS, ScanJobs.MAX_RESULTS);
    }

    @Test
    public void clampingHonoursTheTable() {
        assertEquals(ScanLimits.MIN_RADIUS, ScanLimits.clampRadius(0));
        assertEquals(ScanLimits.MIN_RADIUS, ScanLimits.clampRadius(-50));
        assertEquals(ScanLimits.MAX_RADIUS, ScanLimits.clampRadius(9999));
        assertEquals(ScanLimits.MIN_RESULTS, ScanLimits.clampResults(0));
        assertEquals(ScanLimits.MAX_RESULTS, ScanLimits.clampResults(999999));
    }
}
