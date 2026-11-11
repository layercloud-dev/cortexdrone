package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.visuals.VisualLimits;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The {@code set_light} schema and the aura applier must share one range.
 *
 * <p>Regression: the schema advertised radius 1..32 / level 0..15 while
 * {@link ir.layercloud.cortexdrone.action.visuals.impl.LightingAura} silently
 * clamped to 2..12 / 4..15.</p>
 */
public class VisualsLimitAgreementTest {
    private static ToolSpec lightSpec() {
        for (ToolSpec spec : VisualsTools.specs()) {
            if ("set_light".equals(spec.getName())) return spec;
        }
        fail("set_light is not declared");
        return null;
    }

    private static int bound(String field, String key) {
        return lightSpec().getParameters().getAsJsonObject("properties")
                .getAsJsonObject(field).get(key).getAsInt();
    }

    @Test
    public void theAuraSchemaAdvertisesTheAppliedRadius() {
        assertEquals(VisualLimits.MIN_RADIUS, bound("radius", "minimum"));
        assertEquals(VisualLimits.MAX_RADIUS, bound("radius", "maximum"));
        assertEquals(VisualLimits.DEFAULT_RADIUS, bound("radius", "default"));
    }

    @Test
    public void theAuraSchemaAdvertisesTheAppliedLevel() {
        assertEquals(VisualLimits.MIN_LEVEL, bound("level", "minimum"));
        assertEquals(VisualLimits.MAX_LEVEL, bound("level", "maximum"));
        assertEquals(VisualLimits.DEFAULT_LEVEL, bound("level", "default"));
    }
}
