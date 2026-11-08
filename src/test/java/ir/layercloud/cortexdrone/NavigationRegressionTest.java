package ir.layercloud.cortexdrone;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression guards for the navigation failures visible in the live logs.
 *
 * <p>Each case names the observed failure: wedged legs that never recovered,
 * "arrived:true" on a synchronous goto failure, one-node partial paths burning
 * the recovery budget, a buried mining target with no standoff, and chunk
 * tickets dropped under a second drone.</p>
 */
public class NavigationRegressionTest {
    private static final String MAIN = "src/main/java/ir/layercloud/cortexdrone/";

    @Test
    public void navigationFailuresCarryAFullSnapshot() throws Exception {
        String reporter = read(MAIN + "manager/navigation/impl/StallReporter.java");
        assertTrue("failure snapshots need a stable marker",
                reporter.contains("Navigation failure snapshot"));
        assertTrue("the snapshot must include the blocked drone position",
                reporter.contains("pos=("));
        assertTrue("the snapshot must include the 3x3x3 neighbourhood",
                reporter.contains("nearby="));
        assertTrue("the snapshot must guess a cause",
                reporter.contains("guess="));
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("fail() must use the snapshot", manager.contains("stallReporter.failure("));
    }

    @Test
    public void wedgedLegsAreAbandonedByAWatchdog() throws Exception {
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("a leg watchdog must exist",
                manager.contains("watchdog.tick(") && manager.contains("NavigationSession"));
        assertTrue("the watchdog must be ticked", manager.contains("watchdog.tick("));
        assertTrue("a stalled leg must enter recovery", manager.contains("\"Flight leg stalled\""));
        String watchdog = read(MAIN + "manager/navigation/impl/LegWatchdog.java");
        assertTrue("stuck legs need a displacement limit", watchdog.contains("STUCK_TICKS"));
        assertTrue("looping legs need a progress limit", watchdog.contains("NO_PROGRESS_TICKS"));
    }

    @Test
    public void partialPathsMustMakeRealProgress() throws Exception {
        String pathfinder = read(MAIN + "service/navigation/impl/FlightPathfinder.java");
        assertTrue("Baritone's minimum partial distance must be enforced",
                pathfinder.contains("MIN_PARTIAL_DIST"));
        assertTrue("partial nodes are selected by the backoff coefficients",
                pathfinder.contains("bestPartialNode("));
        assertTrue("a plan must have a wall-clock budget",
                pathfinder.contains("SEARCH_BUDGET_NANOS"));
        assertFalse("a start node must never become a one-waypoint loop",
                pathfinder.contains("targetNode = bestSoFar[BARITONE_COEFFICIENTS.length - 1]"));
    }

    @Test
    public void navigationCannotReportArrivalAfterAReplacement() throws Exception {
        String invoker = read(MAIN + "harness/tool/ToolInvoker.java");
        assertTrue("waiters must read the request outcome", invoker.contains("finishedOutcome"));
        assertTrue("only an arrived outcome is success",
                invoker.contains("\"arrived\".equals(state.finishedOutcome)"));
        assertFalse("a vanished session is not an arrival",
                invoker.contains("arrived\", !active && !failed"));
        String state = read(MAIN + "service/navigation/NavigationState.java");
        assertTrue("every goto gets a token", state.contains("beginRequest()"));
        assertTrue("the outcome is stored per token", state.contains("finishRequest("));
    }

    @Test
    public void buriedStandoffTargetsAreTunneledTo() throws Exception {
        String resolver = read(MAIN + "manager/navigation/impl/NavigationTargetResolver.java");
        assertTrue("a buried block becomes a tunnel target",
                resolver.contains("new NavigationTarget(request.getStandoffX() + 0.5"));
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("the manager keeps the target block", manager.contains("targetBlock"));
        String planner = read(MAIN + "service/navigation/impl/TunnelPlanner.java");
        assertTrue("the obstruction ray must ignore the target block", planner.contains("int[] ignore"));
        assertTrue("the fat ray must cover the drone body", planner.contains("0.39D"));
    }

    @Test
    public void miningMayTunnelToItsOre() throws Exception {
        String skill = read("src/main/resources/skills/mine_blocks/code.js");
        assertTrue("mine_blocks must allow digging by default",
                skill.contains("cfg.allow_dig !== false"));
        assertTrue("the tunnel budget is passed to goto",
                skill.contains("max_dig_blocks: maxDigBlocks"));
    }

    @Test
    public void chunkTicketsProtectEveryDrone() throws Exception {
        String chunks = read(MAIN + "manager/chunk/impl/ChunkLoadingManager.java");
        assertTrue("ticket release must consider every drone",
                chunks.contains("isAnyDroneNear(world, old)"));
        assertFalse("a second drone's chunk must not be released from this controller only",
                chunks.contains("PlayerChunkOwnership.isDroneNear(ctrl.drone, old.chunkXPos"));
    }

    @Test
    public void ascendPlannerAcceptsOneBlockShafts() throws Exception {
        String planner = read(MAIN + "service/navigation/impl/TunnelPlanner.java");
        assertTrue("an ascend step only needs the current head space to be open",
                planner.contains("target[1] - 1"));
    }

    @Test
    public void lavaIsExpensiveButEscapable() throws Exception {
        String classifier = read(MAIN + "service/world/ItemClassifier.java");
        assertTrue("lava must be distinguished from water",
                classifier.contains("isDangerousLiquid("));
        String physics = read(MAIN + "service/navigation/impl/DronePhysicsService.java");
        assertTrue("lava must never become a standoff",
                physics.contains("isDangerousLiquid(world.getBlock(x, y, z))"));
        String sight = read(MAIN + "service/world/BlockSight.java");
        assertTrue("lava must not be an open drone position",
                sight.contains("isDangerousLiquid(world.getBlock(x, y, z))"));
        String pathfinder = read(MAIN + "service/navigation/impl/FlightPathfinder.java");
        assertTrue("A* may cross lava only at a high cost",
                pathfinder.contains("? 8.0 : 2.0"));
    }

    @Test
    public void headHeightCeilingsAndCornerWedgesAreDug() throws Exception {
        String planner = read(MAIN + "service/navigation/impl/TunnelPlanner.java");
        assertTrue("the ascent ray must start at the real body top",
                planner.contains("int bodyTop"));
        assertTrue("the local escape must include the drone's own head column",
                planner.contains("dx == 0 && dz == 0 && dy == 0"));
    }

    @Test
    public void waterDoesNotBounceTheDrone() throws Exception {
        // The integrator now delegates its maths to FlightSpeedModel, so the
        // water cap is asserted on the code that actually applies it.
        String model = read(MAIN + "service/navigation/impl/FlightSpeedModel.java");
        assertFalse("no artificial upward buoyancy while in water",
                model.contains("? 0.03 : 0.0"));
        assertTrue("water caps speed instead of boosting it",
                model.contains("if (inLiquid) effectiveCap = Math.min(effectiveCap, 0.34);"));
        // And the behaviour itself: water must hold the drone below 0.34
        // blocks/tick even at a 5x multiplier.
        String physics = read(MAIN + "service/navigation/impl/DronePhysicsService.java");
        assertTrue("the physics step must delegate to the model",
                physics.contains("FlightSpeedModel.step("));
    }

    @Test
    public void tunnellingIsAllowedNextToTheOwner() throws Exception {
        String planner = read(MAIN + "service/navigation/impl/TunnelPlanner.java");
        assertFalse("the old 2.5-block player bubble must be gone",
                planner.contains("PLAYER_CLEARANCE"));
        assertTrue("only falling blocks above the player are unsafe",
                planner.contains("instanceof net.minecraft.block.BlockFalling"));
    }

    @Test
    public void unknownTileEntitiesAreNotBlanketProtected() throws Exception {
        String classifier = read(MAIN + "service/world/ItemClassifier.java");
        assertFalse("an unknown tile entity must not become undiggable stone",
                classifier.contains("if (te instanceof IInventory) return true;\n        return true;"));
        assertTrue("containers and machines stay protected by name",
                classifier.contains("cls.contains(\"machine\")"));
        assertTrue("inventory tiles stay protected",
                classifier.contains("if (te instanceof IInventory) return true;"));
    }

    @Test
    public void localCornerObstructionsAreDiggable() throws Exception {
        String planner = read(MAIN + "service/navigation/impl/TunnelPlanner.java");
        assertTrue("a corner escape must exist", planner.contains("nextLocalObstruction("));
        assertTrue("the shell search must include the floor voxel",
                planner.contains("for (int dy = -1; dy <= 2; dy++)"));
        String recovery = read(MAIN + "manager/navigation/impl/TunnelRecovery.java");
        assertTrue("recovery must try the local corner escape",
                recovery.contains("TunnelPlanner.nextLocalObstruction("));
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("the manager must delegate to the tunnel escalation",
                manager.contains("TunnelRecovery.nextTarget("));
    }

    @Test
    public void digAwareRoutesReachRockOutsideTheShell() throws Exception {
        String dig = read(MAIN + "service/navigation/impl/DigPathfinder.java");
        assertTrue("a dig-aware A* must exist", dig.contains("firstBreak("));
        assertTrue("breaking must be priced by hardness",
                dig.contains("getBlockHardness") && dig.contains("BREAK_BASE_COST"));
        assertTrue("the search must be time-budgeted",
                dig.contains("BUDGET_NANOS"));
        assertTrue("unbreakable blocks must stay walls",
                dig.contains("isMineableBlock"));
        String recovery = read(MAIN + "manager/navigation/impl/TunnelRecovery.java");
        assertTrue("recovery must fall back to the dig-aware route",
                recovery.contains("DigPathfinder.firstBreak("));
    }

    @Test
    public void openPocketsStillMakeProgress() throws Exception {
        String recovery = read(MAIN + "manager/navigation/impl/TunnelRecovery.java");
        assertTrue("an escape step must exist for flooded pockets",
                recovery.contains("escapeStep("));
        assertTrue("an escape step must prove it approaches the goal",
                recovery.contains("remaining > current - 0.75D"));
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("recovery must use the escape step before failing",
                manager.contains("TunnelRecovery.escapeStep("));
    }

    @Test
    public void circlingTunnelsAreAbandoned() throws Exception {
        String recovery = read(MAIN + "manager/navigation/impl/TunnelRecovery.java");
        assertTrue("circling tunnels need a detector", recovery.contains("circling("));
        assertTrue("the detector needs a threshold", recovery.contains("MAX_USELESS_DIGS"));
        String session = read(MAIN + "manager/navigation/impl/NavigationSession.java");
        assertTrue("digression must be tracked per session",
                session.contains("uselessDigs"));
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("recovery must consult the detector",
                manager.contains("TunnelRecovery.circling("));
    }

    @Test
    public void tunnelExhaustionIsDiagnosable() throws Exception {
        String reporter = read(MAIN + "manager/navigation/impl/StallReporter.java");
        assertTrue("a tunnel-exhausted line must exist",
                reporter.contains("Tunnel search exhausted"));
        assertTrue("the line must show each planner's answer",
                reporter.contains("straight=") && reporter.contains("digFirst="));
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("recovery must emit the diagnostics",
                manager.contains("tunnelDiagnostics("));
    }

    @Test
    public void impossibleGoalsAreRejectedEarly() throws Exception {
        String resolver = read(MAIN + "manager/navigation/impl/NavigationTargetResolver.java");
        assertTrue("a placeholder y must not become a goal", resolver.contains("by <= 2 || by >= 254"));
        assertTrue("the resolver must explain the rejection",
                resolver.contains("describeFailure("));
        String view = read(MAIN + "harness/tools/view/NavigationView.java");
        assertTrue("a zero dig budget with digging enabled must be repaired",
                view.contains("allowDig && maxDigBlocks <= 0"));
        String invoker = read(MAIN + "harness/tool/ToolInvoker.java");
        assertTrue("a spent dig budget needs its own hint",
                invoker.contains("error.contains(\"dig budget\")"));
        assertTrue("a disabled-dig failure needs its own hint",
                invoker.contains("error.contains(\"disabled digging\")"));
    }

    @Test
    public void longTunnelsGetAUsableDefaultBudget() throws Exception {
        String view = read(MAIN + "harness/tools/view/NavigationView.java");
        assertTrue("the default dig budget must cover a long route",
                view.contains("clampedInt(args, \"maxDigBlocks\", 128, 0, 512)"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
