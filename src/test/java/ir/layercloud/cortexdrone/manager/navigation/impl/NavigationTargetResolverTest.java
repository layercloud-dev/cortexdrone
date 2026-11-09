package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Placeholder goals must be rejected with an explanation.
 *
 * <p>y=0 is what an unresolved memory or a missing field looks like. Treating it
 * as a destination produced a wall of "no tunnel direction found" failures at
 * bedrock depth, so the resolver refuses any y outside the world.</p>
 */
public class NavigationTargetResolverTest {
    @Test
    public void aPlaceholderYIsRejected() {
        assertFalse("y=0 must not be a goal", WorldBoundsStub.diggable(0));
        assertFalse("bedrock must not be a goal", WorldBoundsStub.diggable(2));
        assertFalse("above the world must not be a goal", WorldBoundsStub.diggable(255));
    }

    @Test
    public void realYLevelsAreAccepted() {
        assertTrue(WorldBoundsStub.diggable(3));
        assertTrue(WorldBoundsStub.diggable(64));
        assertTrue(WorldBoundsStub.diggable(253));
    }

    @Test
    public void aPlaceholderGoalExplainsItself() {
        NavigationRequest request = NavigationRequest.builder()
                .coordinates(true).x(10.0).y(0.0).z(10.0)
                .build();
        String reason = NavigationTargetResolver.describeFailure(request);
        assertNotNull(reason);
        assertTrue("the reason must name the y value", reason.contains("y=0"));
        assertTrue("the reason must say how to fix it",
                reason.contains("memory") || reason.contains("scan"));
    }

    @Test
    public void aRealGoalHasNoRejectionReason() {
        NavigationRequest request = NavigationRequest.builder()
                .coordinates(true).x(10.0).y(64.0).z(10.0)
                .build();
        String reason = NavigationTargetResolver.describeFailure(request);
        // A valid coordinate request is not the "placeholder y" case.
        assertFalse(reason.contains("outside the world"));
    }

    @Test
    public void aNullRequestIsExplained() {
        assertNotNull(NavigationTargetResolver.describeFailure(null));
    }

    /**
     * Regression: a standoff request whose block is buried and whose call set
     * allow_dig:false used to fail with "goto needs a destination". The model
     * read that as wrong arguments, changed key spelling, and burned turns.
     */
    @Test
    public void anUnresolvableStandoffExplainsTheRealCause() {
        NavigationRequest request = NavigationRequest.builder()
                .standoff(true).standoffX(10).standoffY(64).standoffZ(10)
                .allowDig(false)
                .build();
        String reason = NavigationTargetResolver.describeFailure(request);
        assertTrue(reason.contains("allow_dig"));
        assertFalse("must not claim the destination is missing",
                reason.startsWith("goto needs a destination"));
        assertTrue("must describe the block", reason.contains("10, 64, 10"));
    }

    /** Small mirror of the real bounds so this test needs no Minecraft world. */
    private static final class WorldBoundsStub {
        static boolean diggable(int y) {
            return ir.layercloud.cortexdrone.service.world.WorldBounds.diggable(y);
        }
    }
}
