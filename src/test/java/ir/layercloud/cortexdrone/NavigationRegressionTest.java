package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.service.navigation.NavigationLimits;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression guards for the navigation failures visible in the live logs.
 *
 * <p>Each case names the observed failure: wedged legs that never recovered,
 * "arrived:true" on a synchronous goto failure, one-node partial paths burning
 * the recovery budget, a buried mining target with no standoff, and chunk
 * tickets dropped under a second drone.</p>
 *
 * <p>Navigation is split across several classes inside
 * {@code manager/navigation/impl} and {@code service/navigation/impl}. The
 * guards therefore assert on the whole package: a rule must exist in the layer,
 * not in one particular file, so the code can be decomposed without the test
 * failing for the wrong reason.</p>
 */
public class NavigationRegressionTest {
    private static final String NAV_MANAGER = SourceTree.MAIN + "manager/navigation/impl";
    private static final String NAV_SERVICE = SourceTree.MAIN + "service/navigation/impl";

    @Test
    public void navigationFailuresCarryAFullSnapshot() {
        String reporter = SourceTree.require(NAV_MANAGER + "/StallReporter.java");
        assertTrue("failure snapshots need a stable marker",
                reporter.contains("Navigation failure snapshot"));
        assertTrue("the snapshot must include the blocked drone position",
                reporter.contains("pos=("));
        assertTrue("the snapshot must include the 3x3x3 neighbourhood",
                reporter.contains("nearby="));
        assertTrue("the snapshot must guess a cause",
                reporter.contains("guess="));
        assertTrue("fail() must use the snapshot",
                SourceTree.readPackage(NAV_MANAGER).contains("stallReporter.failure("));
    }

    @Test
    public void wedgedLegsAreAbandonedByAWatchdog() {
        String manager = SourceTree.readPackage(NAV_MANAGER);
        assertTrue("the watchdog must be ticked", manager.contains("watchdog.tick("));
        assertTrue("a stalled leg must enter recovery", manager.contains("\"Flight leg stalled\""));
        String watchdog = SourceTree.require(NAV_MANAGER + "/LegWatchdog.java");
        assertTrue("stuck legs need a displacement limit", watchdog.contains("STUCK_TICKS"));
        assertTrue("looping legs need a progress limit", watchdog.contains("NO_PROGRESS_TICKS"));
    }

    @Test
    public void partialPathsMustMakeRealProgress() {
        // The search lives in FlightAStar; assert against the live implementation,
        // not a leftover copy in the orchestrator.
        String search = SourceTree.require(NAV_SERVICE + "/FlightAStar.java");
        assertTrue("a partial route needs a minimum distance",
                search.contains("MIN_PARTIAL_DIST"));
        assertTrue("partial nodes are selected by the backoff coefficients",
                search.contains("bestPartialNode("));
        assertTrue("a plan must have a wall-clock budget",
                search.contains("SEARCH_BUDGET_NANOS"));
        assertFalse("a start node must never become a one-waypoint loop",
                search.contains("targetNode = bestSoFar[BACKOFF_COEFFICIENTS.length - 1]"));

        // The orchestrator must not keep a second copy of the search rules.
        String orchestrator = SourceTree.require(NAV_SERVICE + "/FlightPathfinder.java");
        assertFalse("the search rules must live in exactly one place",
                orchestrator.contains("private static AStarNode bestPartialNode("));
        assertFalse("search constants must not be duplicated in the orchestrator",
                orchestrator.contains("BARITONE_COEFFICIENTS"));
    }

    @Test
    public void navigationCannotReportArrivalAfterAReplacement() {
        String invoker = SourceTree.require(SourceTree.MAIN + "harness/tool/ToolInvoker.java");
        assertTrue("waiters must read the request outcome", invoker.contains("finishedOutcome"));
        assertTrue("only an arrived outcome is success",
                invoker.contains("\"arrived\".equals(state.finishedOutcome)"));
        assertFalse("a vanished session is not an arrival",
                invoker.contains("arrived\", !active && !failed"));
        String state = SourceTree.require(SourceTree.MAIN + "service/navigation/NavigationState.java");
        assertTrue("every goto gets a token", state.contains("beginRequest()"));
        assertTrue("the outcome is stored per token", state.contains("finishRequest("));
    }

    @Test
    public void buriedStandoffTargetsAreTunneledTo() {
        String resolver = SourceTree.require(NAV_MANAGER + "/NavigationTargetResolver.java");
        assertTrue("a buried block becomes a tunnel target",
                resolver.contains("new NavigationTarget(request.getStandoffX()"));
        assertTrue("the target is the block centre, not its corner",
                resolver.contains("DroneGeometry.CELL_CENTER"));
        assertTrue("the manager keeps the target block",
                SourceTree.readPackage(NAV_MANAGER).contains("targetBlock"));
        String planner = SourceTree.require(NAV_SERVICE + "/TunnelPlanner.java");
        assertTrue("the obstruction ray must ignore the target block", planner.contains("int[] ignore"));
        assertTrue("the fat ray must cover the drone body", planner.contains("0.39D"));
    }

    @Test
    public void miningMayTunnelToItsOre() {
        String skill = SourceTree.read(SourceTree.RESOURCES + "skills/mine_blocks/code.js");
        assertTrue("mine_blocks must allow digging by default",
                skill.contains("cfg.allow_dig !== false"));
        assertTrue("the tunnel budget is passed to goto",
                skill.contains("max_dig_blocks: maxDigBlocks"));
    }

    @Test
    public void chunkTicketsProtectEveryDrone() {
        // The release rule lives in TicketPlanApplier; assert it across the
        // chunk package so a later split cannot drop it.
        String chunks = SourceTree.readPackage(SourceTree.MAIN + "manager/chunk");
        assertTrue("ticket release must consider every drone",
                chunks.contains("isAnyDroneNear("));
        assertFalse("a second drone's chunk must not be released from this controller only",
                chunks.contains("PlayerChunkOwnership.isDroneNear(ctrl.drone, old.chunkXPos"));
    }

    @Test
    public void ascendPlannerAcceptsOneBlockShafts() {
        String planner = SourceTree.require(NAV_SERVICE + "/TunnelPlanner.java");
        assertTrue("an ascend step only needs the current head space to be open",
                planner.contains("target[1] - 1"));
    }

    @Test
    public void lavaIsExpensiveButEscapable() {
        String classifier = SourceTree.require(SourceTree.MAIN + "service/world/ItemClassifier.java");
        assertTrue("lava must be distinguished from water",
                classifier.contains("isDangerousLiquid("));
        String physics = SourceTree.require(NAV_SERVICE + "/DronePhysicsService.java");
        assertTrue("lava must never become a standoff",
                physics.contains("isDangerousLiquid(world.getBlock(x, y, z))"));
        String sight = SourceTree.require(SourceTree.MAIN + "service/world/BlockSight.java");
        assertTrue("lava must not be an open drone position",
                sight.contains("isDangerousLiquid(world.getBlock(x, y, z))"));
        String pathfinder = SourceTree.require(NAV_SERVICE + "/FlightPathfinder.java");
        assertTrue("A* may cross lava only at a high cost",
                pathfinder.contains("LAVA_STEP_COST"));
    }

    @Test
    public void headHeightCeilingsAndCornerWedgesAreDug() {
        String planner = SourceTree.require(NAV_SERVICE + "/TunnelPlanner.java");
        assertTrue("the ascent ray must start at the real body top",
                planner.contains("int bodyTop"));
        assertTrue("the local escape must include the drone's own head column",
                planner.contains("dx == 0 && dz == 0 && dy == 0"));
    }

    @Test
    public void waterDoesNotBounceTheDrone() {
        // The integrator delegates its maths to FlightSpeedModel, so the water
        // cap is asserted on the code that actually applies it.
        String model = SourceTree.require(NAV_SERVICE + "/FlightSpeedModel.java");
        assertFalse("no artificial upward buoyancy while in water",
                model.contains("? 0.03 : 0.0"));
        assertTrue("water caps speed instead of boosting it",
                model.contains("WATER_SPEED_CAP"));
        String physics = SourceTree.require(NAV_SERVICE + "/DronePhysicsService.java");
        assertTrue("the physics step must delegate to the model",
                physics.contains("FlightSpeedModel.step("));
    }

    @Test
    public void tunnellingIsAllowedNextToTheOwner() {
        String planner = SourceTree.require(NAV_SERVICE + "/TunnelPlanner.java");
        assertFalse("the old 2.5-block player bubble must be gone",
                planner.contains("PLAYER_CLEARANCE"));
        assertTrue("only falling blocks above the player are unsafe",
                planner.contains("instanceof net.minecraft.block.BlockFalling"));
    }

    @Test
    public void unknownTileEntitiesAreNotBlanketProtected() {
        String classifier = SourceTree.require(SourceTree.MAIN + "service/world/ItemClassifier.java");
        assertFalse("an unknown tile entity must not become undiggable stone",
                classifier.contains("if (te instanceof IInventory) return true;\n        return true;"));
        assertTrue("containers and machines stay protected by name",
                classifier.contains("cls.contains(\"machine\")"));
        assertTrue("inventory tiles stay protected",
                classifier.contains("if (te instanceof IInventory) return true;"));
    }

    @Test
    public void localCornerObstructionsAreDiggable() {
        String planner = SourceTree.require(NAV_SERVICE + "/TunnelPlanner.java");
        assertTrue("a corner escape must exist", planner.contains("nextLocalObstruction("));
        assertTrue("the shell search must include the floor voxel",
                planner.contains("for (int dy = -1; dy <= 2; dy++)"));
        String recovery = SourceTree.require(NAV_MANAGER + "/TunnelRecovery.java");
        assertTrue("recovery must try the local corner escape",
                recovery.contains("TunnelPlanner.nextLocalObstruction("));
        assertTrue("the manager must delegate to the tunnel escalation",
                SourceTree.readPackage(NAV_MANAGER).contains("TunnelRecovery.nextTarget("));
    }

    @Test
    public void digAwareRoutesReachRockOutsideTheShell() {
        String dig = SourceTree.require(NAV_SERVICE + "/DigPathfinder.java");
        assertTrue("a dig-aware A* must exist", dig.contains("firstBreak("));
        assertTrue("breaking must be priced by hardness",
                dig.contains("getBlockHardness") && dig.contains("BREAK_BASE_COST"));
        assertTrue("the search must be time-budgeted",
                dig.contains("BUDGET_NANOS"));
        assertTrue("unbreakable blocks must stay walls",
                dig.contains("isMineableBlock"));
        String recovery = SourceTree.require(NAV_MANAGER + "/TunnelRecovery.java");
        assertTrue("recovery must fall back to the dig-aware route",
                recovery.contains("DigPathfinder.firstBreak("));
    }

    @Test
    public void openPocketsStillMakeProgress() {
        String recovery = SourceTree.require(NAV_MANAGER + "/TunnelRecovery.java");
        assertTrue("an escape step must exist for flooded pockets",
                recovery.contains("escapeStep("));
        assertTrue("an escape step must prove it approaches the goal",
                recovery.contains("remaining > current - 0.75D"));
        assertTrue("recovery must use the escape step before failing",
                SourceTree.readPackage(NAV_MANAGER).contains("TunnelRecovery.escapeStep("));
    }

    @Test
    public void circlingTunnelsAreAbandoned() {
        String recovery = SourceTree.require(NAV_MANAGER + "/TunnelRecovery.java");
        assertTrue("circling tunnels need a detector", recovery.contains("circling("));
        assertTrue("the detector needs a threshold", recovery.contains("MAX_USELESS_DIGS"));
        String session = SourceTree.require(NAV_MANAGER + "/NavigationSession.java");
        assertTrue("digression must be tracked per session",
                session.contains("uselessDigs"));
        assertTrue("recovery must consult the detector",
                SourceTree.readPackage(NAV_MANAGER).contains("TunnelRecovery.circling("));
    }

    @Test
    public void tunnelExhaustionIsDiagnosable() {
        String reporter = SourceTree.require(NAV_MANAGER + "/StallReporter.java");
        assertTrue("a tunnel-exhausted line must exist",
                reporter.contains("Tunnel search exhausted"));
        assertTrue("the line must show each planner's answer",
                reporter.contains("straight=") && reporter.contains("digFirst="));
        assertTrue("recovery must emit the diagnostics",
                SourceTree.readPackage(NAV_MANAGER).contains("tunnelDiagnostics("));
    }

    @Test
    public void impossibleGoalsAreRejectedEarly() {
        // The placeholder-y rule is covered behaviourally by
        // NavigationTargetResolverTest; here we only check the wiring.
        String resolver = SourceTree.require(NAV_MANAGER + "/NavigationTargetResolver.java");
        assertTrue("the resolver must explain the rejection",
                resolver.contains("describeFailure("));
        String view = SourceTree.require(SourceTree.MAIN + "harness/tools/view/NavigationView.java");
        assertTrue("a zero dig budget with digging enabled must be repaired",
                view.contains("allowDig && maxDigBlocks <= 0"));
        String invoker = SourceTree.require(SourceTree.MAIN + "harness/tool/ToolInvoker.java");
        assertTrue("a spent dig budget needs its own hint",
                invoker.contains("error.contains(\"dig budget\")"));
        assertTrue("a disabled-dig failure needs its own hint",
                invoker.contains("error.contains(\"disabled digging\")"));
    }

    @Test
    public void longTunnelsGetAUsableDefaultBudget() {
        String view = SourceTree.require(SourceTree.MAIN + "harness/tools/view/NavigationView.java");
        assertTrue("the default dig budget must come from the policy owner",
                view.contains("NavigationLimits.DEFAULT_DIG_BLOCKS"));
        assertTrue("the default dig budget must cover a long route",
                NavigationLimits.DEFAULT_DIG_BLOCKS >= 64);
    }
}
