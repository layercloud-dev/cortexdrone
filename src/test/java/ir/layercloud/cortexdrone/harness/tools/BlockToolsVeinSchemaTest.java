package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolCatalog;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.world.VeinLimits;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The public schema must actually advertise vein mode, with matching bounds.
 */
public class BlockToolsVeinSchemaTest {
    private static JsonObject breakBlockSchema() {
        for (ToolSpec spec : ToolCatalog.standard()) {
            if ("break_block".equals(spec.getName())) return spec.getParameters();
        }
        throw new AssertionError("break_block is not declared");
    }

    private static JsonObject property(JsonObject schema, String name) {
        return schema.getAsJsonObject("properties").getAsJsonObject(name);
    }

    @Test
    public void veinOptionsAreDeclaredWithDefaults() {
        JsonObject schema = breakBlockSchema();

        JsonObject vein = property(schema, "vein");
        assertNotNull("vein must be a schema property", vein);
        assertEquals("boolean", vein.get("type").getAsString());
        assertTrue(vein.get("description").getAsString().contains("cluster"));

        assertEquals(VeinLimits.DEFAULT_RADIUS,
                property(schema, "vein_radius").get("default").getAsInt());
        assertEquals(VeinLimits.DEFAULT_BLOCKS,
                property(schema, "max_vein_blocks").get("default").getAsInt());
    }

    @Test
    public void schemaBoundsMatchThePolicyClass() {
        JsonObject schema = breakBlockSchema();
        assertEquals(VeinLimits.MIN_RADIUS,
                property(schema, "vein_radius").get("minimum").getAsInt());
        assertEquals(VeinLimits.MAX_RADIUS,
                property(schema, "vein_radius").get("maximum").getAsInt());
        assertEquals(VeinLimits.MIN_BLOCKS,
                property(schema, "max_vein_blocks").get("minimum").getAsInt());
        assertEquals(VeinLimits.MAX_BLOCKS,
                property(schema, "max_vein_blocks").get("maximum").getAsInt());
    }
}
