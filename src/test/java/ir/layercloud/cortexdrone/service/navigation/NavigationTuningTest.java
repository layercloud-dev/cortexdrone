package ir.layercloud.cortexdrone.service.navigation;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Navigator tuning lives in one place and the tick uses it.
 *
 * <p>Regression: {@code NavigateAction.tick} carried bare 6.0/20.0/24.0/18.0/
 * 32.0/16.0/4.5/0.85/0.90/12/999.0 values, so a stride or repath change had to
 * be found by eye.</p>
 */
public class NavigationTuningTest {
    private static final String ACTION =
            "src/main/java/ir/layercloud/cortexdrone/action/navigation/impl/NavigateAction.java";

    private static String source() throws Exception {
        return new String(Files.readAllBytes(Paths.get(ACTION)), StandardCharsets.UTF_8);
    }

    @Test
    public void navigateActionReadsTheTuningOwner() throws Exception {
        String source = source();
        assertTrue(source.contains("NavigationTuning.SURFACE_DETOUR_HEIGHT"));
        assertTrue(source.contains("NavigationTuning.SURFACE_DETOUR_DISTANCE"));
        assertTrue(source.contains("NavigationTuning.SURFACE_ANCHOR_RADIUS"));
        assertTrue(source.contains("NavigationTuning.STRIDE_TRIGGER_OPEN_SKY"));
        assertTrue(source.contains("NavigationTuning.STRIDE_STEP_OPEN_SKY"));
        assertTrue(source.contains("NavigationTuning.STRIDE_CLEARANCE"));
        assertTrue(source.contains("NavigationTuning.NO_GOAL_SENTINEL"));
        assertTrue(source.contains("NavigationTuning.REPATH_DISTANCE"));
        assertTrue(source.contains("NavigationTuning.WAYPOINT_RETARGET_DISTANCE"));
        assertTrue(source.contains("NavigationTuning.STUCK_REPATH_TICKS"));
        assertTrue(source.contains("NavigationTuning.REPATH_COOLDOWN_NEAR"));
        assertTrue(source.contains("NavigationTuning.ANCHOR_HEIGHT_OFFSET"));
        assertTrue(source.contains("NavigationTuning.ESCAPE_HEIGHT_OFFSET"));
        assertTrue(source.contains("NavigationTuning.STOP_DELTA"));
        assertTrue(source.contains("NavigationTuning.BRAKE_DAMPING"));
        assertTrue(source.contains("NavigationTuning.BLOCKED_STUCK_TICKS"));
        assertTrue(source.contains("NavigationTuning.WAYPOINT_DELTA"));
        assertTrue(source.contains("NavigationTuning.STRIDE_SAMPLE_STEP"));
        assertTrue(source.contains("NavigationTuning.BODY_HEIGHT"));
    }

    @Test
    public void theTuningValuesAreStable() {
        assertEquals(6.0, NavigationTuning.SURFACE_DETOUR_HEIGHT, 0.0);
        assertEquals(20.0, NavigationTuning.SURFACE_DETOUR_DISTANCE, 0.0);
        assertEquals(24.0, NavigationTuning.SURFACE_ANCHOR_RADIUS, 0.0);
        assertEquals(18.0, NavigationTuning.STRIDE_TRIGGER_OPEN_SKY, 0.0);
        assertEquals(32.0, NavigationTuning.STRIDE_TRIGGER_SHELTERED, 0.0);
        assertEquals(16.0, NavigationTuning.STRIDE_STEP_OPEN_SKY, 0.0);
        assertEquals(24.0, NavigationTuning.STRIDE_STEP_SHELTERED, 0.0);
        assertEquals(4.5, NavigationTuning.STRIDE_CLEARANCE, 0.0);
        assertEquals(999.0, NavigationTuning.NO_GOAL_SENTINEL, 0.0);
        assertEquals(0.85, NavigationTuning.REPATH_DISTANCE, 0.0);
        assertEquals(0.90, NavigationTuning.WAYPOINT_RETARGET_DISTANCE, 0.0);
        assertEquals(12, NavigationTuning.STUCK_REPATH_TICKS);
        assertEquals(8, NavigationTuning.REPATH_COOLDOWN_NEAR);
        assertEquals(12, NavigationTuning.REPATH_COOLDOWN_FAR);
        assertEquals(1.0, NavigationTuning.ANCHOR_HEIGHT_OFFSET, 0.0);
        assertEquals(2.5, NavigationTuning.ESCAPE_HEIGHT_OFFSET, 0.0);
        assertEquals(0.05f, NavigationTuning.STOP_DELTA, 0.0);
        assertEquals(0.3, NavigationTuning.BRAKE_DAMPING, 0.0);
        assertEquals(6, NavigationTuning.BLOCKED_STUCK_TICKS);
        assertEquals(0.1f, NavigationTuning.WAYPOINT_DELTA, 0.0);
        assertEquals(8.0, NavigationTuning.STRIDE_SAMPLE_STEP, 0.0);
        assertEquals(1, NavigationTuning.BODY_HEIGHT);
    }
}
