package ir.layercloud.cortexdrone.service.world;

import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * The build-site schema and the search must advertise the same radius.
 *
 * <p>Regression: {@code find_build_site} advertised {@code max_radius} 4..64
 * while {@link BuildSiteFinder} silently clamped to an internal 8..40 and
 * reported nothing, so a caller who asked for 64 searched only 40 blocks with no
 * way to tell a cap from a genuinely empty area.</p>
 *
 * <p>The schema assertions read the declared {@link ToolSpec}, not a copy of the
 * numbers, so schema/handler drift fails here.</p>
 */
public class BuildSiteRadiusAgreementTest {
    /** The range the tool schema declares for {@code max_radius}. */
    private static int[] schemaRange() {
        for (ToolSpec spec : ir.layercloud.cortexdrone.harness.tools.WorldTools.specs()) {
            if (!"find_build_site".equals(spec.getName())) continue;
            return new int[]{
                    intProperty(spec, "max_radius", "minimum"),
                    intProperty(spec, "max_radius", "maximum")
            };
        }
        fail("find_build_site is not declared");
        return null;
    }

    private static int[] schemaRange(String tool, String field) {
        ToolSpec spec = spec(tool);
        return new int[]{
                intProperty(spec, field, "minimum"),
                intProperty(spec, field, "maximum")
        };
    }

    private static int intProperty(ToolSpec spec, String field, String key) {
        return spec.getParameters().getAsJsonObject("properties")
                .getAsJsonObject(field).get(key).getAsInt();
    }

    @Test
    public void theSchemaAdvertisesTheRangeTheSearchHonours() {
        int[] range = schemaRange();
        assertEquals("the enforced minimum must equal the advertised minimum",
                range[0], BuildSiteFinder.SCHEMA_MIN_RADIUS);
        assertEquals("the enforced maximum must equal the advertised maximum",
                range[1], BuildSiteFinder.SCHEMA_MAX_RADIUS);
    }

    @Test
    public void anOutOfRangeRequestIsClampedToTheSchemaRange() {
        assertEquals("a request below the minimum clamps up",
                BuildSiteFinder.SCHEMA_MIN_RADIUS, BuildSiteFinder.clampRadius(1));
        assertEquals("a request above the maximum clamps down",
                BuildSiteFinder.SCHEMA_MAX_RADIUS, BuildSiteFinder.clampRadius(500));
        assertEquals("an in-range request is preserved",
                33, BuildSiteFinder.clampRadius(33));
    }

    @Test
    public void noInRangeRequestIsSilentlyNarrowed() {
        int[] range = schemaRange();
        for (int requested = range[0]; requested <= range[1]; requested++) {
            assertEquals("requested " + requested + " must not be narrowed",
                    requested, BuildSiteFinder.clampRadius(requested));
        }
    }

    @Test
    public void theDefaultIsInsideTheAdvertisedRange() {
        int[] range = schemaRange();
        assertTrue(BuildSiteFinder.DEFAULT_RADIUS >= range[0]);
        assertTrue(BuildSiteFinder.DEFAULT_RADIUS <= range[1]);
        assertEquals("the schema default must be the handler default",
                BuildSiteFinder.DEFAULT_RADIUS,
                intProperty(spec("find_build_site"), "max_radius", "default"));
    }

    @Test
    public void theRadiusPolicyHasOneOwner() {
        assertEquals("the build-site radius floor must be the build-site policy floor",
                ScanLimits.MIN_BUILD_SITE_RADIUS, BuildSiteFinder.SCHEMA_MIN_RADIUS);
        assertEquals("the build-site radius ceiling must be the scan policy ceiling",
                ScanLimits.MAX_RADIUS, BuildSiteFinder.SCHEMA_MAX_RADIUS);
        assertEquals("the build-site default must be the scan policy default",
                ScanLimits.DEFAULT_RADIUS, BuildSiteFinder.DEFAULT_RADIUS);
    }

    @Test
    public void theFootprintBoundsAreTheAdvertisedOnes() {
        int[] width = schemaRange("find_build_site", "width");
        assertEquals("the enforced minimum side must equal the advertised minimum",
                width[0], BuildSiteFinder.MIN_SIDE);
        assertEquals("the enforced maximum side must equal the advertised maximum",
                width[1], BuildSiteFinder.MAX_SIDE);
        assertEquals("the search must not accept a footprint the schema rejects",
                WorldBounds.MAX_PLAN_SIDE, BuildSiteFinder.MAX_SIDE);
        assertTrue(BuildSiteFinder.MIN_SIDE < BuildSiteFinder.MAX_SIDE);
    }

    private static ToolSpec spec(String name) {
        for (ToolSpec candidate : ir.layercloud.cortexdrone.harness.tools.WorldTools.specs()) {
            if (name.equals(candidate.getName())) return candidate;
        }
        fail("no spec named " + name);
        return null;
    }
}
