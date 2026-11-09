package ir.layercloud.cortexdrone.service.world.raytrace;

import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.WorldTools;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The raycast schema and the raycaster must agree on the ray length.
 *
 * <p>Regression: {@code raycast_player} advertised {@code max_dist} 1..128 while
 * {@code VoxelRaycaster3D} silently clamped to 4..128, so a request for 1–3
 * blocks was quietly widened with no signal — the same schema/implementation
 * drift that the scan and build-site limits already had.</p>
 */
public class RaycastLimitAgreementTest {
    private static int[] schemaRange() {
        for (ToolSpec spec : WorldTools.specs()) {
            if (!"raycast_player".equals(spec.getName())) continue;
            return new int[]{
                    spec.getParameters().getAsJsonObject("properties")
                            .getAsJsonObject("max_dist").get("minimum").getAsInt(),
                    spec.getParameters().getAsJsonObject("properties")
                            .getAsJsonObject("max_dist").get("maximum").getAsInt()
            };
        }
        fail("raycast_player is not declared");
        return null;
    }

    @Test
    public void theRayLengthBoundsAreTheAdvertisedBounds() {
        int[] range = schemaRange();
        assertEquals("the enforced minimum must equal the advertised minimum",
                range[0], VoxelRaycaster3D.MIN_DISTANCE);
        assertEquals("the enforced maximum must equal the advertised maximum",
                range[1], VoxelRaycaster3D.MAX_DISTANCE);
    }

    @Test
    public void aShortRayIsNotSilentlyWidened() {
        for (int requested = VoxelRaycaster3D.MIN_DISTANCE;
             requested <= VoxelRaycaster3D.MAX_DISTANCE; requested++) {
            assertEquals("a requested ray of " + requested + " must be honoured",
                    (double) requested, VoxelRaycaster3D.clampDistance(requested), 0.0001);
        }
    }

    @Test
    public void outOfRangeRequestsAreClampedToTheAdvertisedRange() {
        assertEquals(VoxelRaycaster3D.MIN_DISTANCE, VoxelRaycaster3D.clampDistance(-5), 0.0001);
        assertEquals(VoxelRaycaster3D.MAX_DISTANCE, VoxelRaycaster3D.clampDistance(9999), 0.0001);
    }
}
