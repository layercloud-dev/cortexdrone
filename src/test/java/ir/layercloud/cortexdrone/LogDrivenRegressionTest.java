package ir.layercloud.cortexdrone;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Guards for the failures visible in the live {@code cortexdrone.log}.
 *
 * <p>These are deliberately source-level where the behaviour needs a live
 * Minecraft world (stuck diagnostics, session progress watchdog, canopy escape)
 * and structural where the JSON shape is asserted at the view boundary.</p>
 */
public class LogDrivenRegressionTest {
    private static final String MAIN = "src/main/java/ir/layercloud/cortexdrone/";

    @Test
    public void stuckDiagnosticsLookTowardTheWaypoint() {
        String physics = SourceTree.require(MAIN
                + "service/navigation/impl/DronePhysicsService.java");
        String reporter = SourceTree.require(MAIN
                + "manager/navigation/impl/StallReporter.java");
        assertTrue("the obstruction description must accept a direction",
                physics.contains("double tx, double ty, double tz"));
        assertTrue("body and head cells must be reported first",
                physics.contains("\"body\"") && physics.contains("\"head\""));
        assertTrue("the stall report must pass the active waypoint",
                reporter.contains("drone.targetX(), drone.targetY(), drone.targetZ()"));
    }

    @Test
    public void aLegChainCannotRunForeverWithoutApproachingTheGoal() {
        String ticker = SourceTree.require(MAIN
                + "manager/navigation/impl/NavigationTicker.java");
        String tuning = SourceTree.require(MAIN + "service/navigation/NavigationTuning.java");
        assertTrue("the ticker needs a session-level progress check",
                ticker.contains("noteSessionProgress("));
        assertTrue("it must fail the request when progress stops",
                ticker.contains("Navigation timed out (no progress toward goal)"));
        assertTrue("the limit is owned by NavigationTuning",
                tuning.contains("NO_PROGRESS_LIMIT_TICKS"));
    }

    @Test
    public void aCanopyStallClimbsOutInsteadOfDiggingOneLeafAtATime() {
        String recovery = SourceTree.require(MAIN
                + "manager/navigation/impl/NavigationRecovery.java");
        String leaf = SourceTree.require(MAIN
                + "action/navigation/impl/ClearLeafObstructionAction.java");
        assertTrue("recovery must try a canopy climb",
                recovery.contains("tryCanopyEscape("));
        assertTrue("the climb must require an air/leaf column",
                recovery.contains("isLeafBlock(block)"));
        assertTrue("leaf clearance must cover more than the body shell",
                leaf.contains("dx = -2; dx <= 2"));
    }

    @Test
    public void stateExposesThePositionAliasScriptsKeepUsing() {
        String state = SourceTree.require(MAIN + "harness/tools/view/StateView.java");
        assertTrue("s.position.x must stop throwing", state.contains("response.add(\"position\", position)"));
        assertTrue("dimension is useful for route selection", state.contains("response.addProperty(\"dimension\""));
    }

    @Test
    public void playerNamesAreValidEntityFilters() {
        String classifier = SourceTree.require(MAIN + "service/world/EntityClassifier.java");
        assertTrue("a player name must not be filtered out before comparison",
                classifier.contains("playerName.toLowerCase(Locale.ROOT).contains(clause)"));
    }

    @Test
    public void digBudgetIsEnforcedAtTheSingleChokePoint() {
        String digger = SourceTree.require(MAIN
                + "manager/navigation/impl/TunnelDigger.java");
        String recovery = SourceTree.require(MAIN
                + "manager/navigation/impl/NavigationRecovery.java");
        assertTrue("the digger itself must stop at the budget",
                digger.contains("session.dug >= session.maxDigBlocks"));
        assertTrue("the pending-dig path must fail cleanly, not overrun",
                recovery.contains("dig budget "));
    }

    @Test
    public void boxReadsAreBoundedAndExposeBothCountNames() {
        String box = SourceTree.require(MAIN + "harness/tools/WorldBlockReadTools.java");
        assertTrue("a huge box must be refused before the server-thread loop",
                box.contains("MAX_BOX_VOLUME"));
        assertTrue("counts must also be available under the shorter key",
                box.contains("out.add(\"counts\", countsJson)"));
    }

    @Test
    public void bundledSkillsMatchThePhrasesPlayersActuallyUse() {
        String returnSkill = SourceTree.read(SourceTree.RESOURCES + "skills/return_to_player/SKILL.md");
        String hunt = SourceTree.read(SourceTree.RESOURCES + "skills/hunt/SKILL.md");
        String chop = SourceTree.read(SourceTree.RESOURCES + "skills/chop_tree/SKILL.md");
        assertTrue("come here must resolve to return_to_player", returnSkill.contains("come here"));
        assertTrue("we need food must resolve to hunt", hunt.contains("we need food"));
        assertTrue("jungle chopping must resolve to chop_tree",
                chop.contains("chop 2stack of jungle wood"));
    }
}
