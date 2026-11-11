package ir.layercloud.cortexdrone;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * Regression guards for the passenger deaths in the live log.
 *
 * <p>Two players were killed by the drone: one "hit the ground too hard" after
 * being carried to a death coordinate, one "fell from a high place" after a low
 * flight into a hillside. The root causes were a dismount with no floor check,
 * a route flown at ground level, and no cave escape that a 1.8-block rider can
 * actually fit through. Each case below pins one of the fixes in place.</p>
 */
public class PassengerSafetyRegressionTest {
    private static final String MAIN = "src/main/java/ir/layercloud/cortexdrone/";
    private static final String SKILLS = "src/main/resources/skills/";

    @Test
    public void dismountingChecksTheLandingBeforeReleasingARider() throws Exception {
        String action = read(MAIN + "action/interact/impl/UnpickEntityAction.java");
        assertTrue("a living rider must be landing-checked",
                action.contains("instanceof net.minecraft.entity.EntityLivingBase"));
        assertTrue("the check must be the shared scanner",
                action.contains("LandingScanner.canDismountHere("));
        assertTrue("an unsafe spot must move, not drop",
                action.contains("InteractionResult.movement("));
        assertTrue("the refusal must explain the danger",
                action.contains("no safe landing column"));
        assertTrue("a caller can accept the risk explicitly",
                action.contains("request.isForce()"));
    }

    @Test
    public void landingScannerRejectsEverythingThatKillsAPassenger() throws Exception {
        String scanner = read(MAIN + "service/navigation/impl/LandingScanner.java");
        assertTrue("lava must be rejected", scanner.contains("isDangerousLiquid"));
        assertTrue("water must be rejected", scanner.contains("isWater("));
        assertTrue("leaves must not be a floor",
                scanner.contains("isLeafBlock(block)"));
        assertTrue("a 2-block pocket is required",
                scanner.contains("no 2-block pocket"));
        assertTrue("the fall line is vanilla-safe",
                scanner.contains("MAX_SAFE_DROP = 2"));
        assertTrue("falling blocks overhead must be refused",
                scanner.contains("BlockFalling"));
        assertTrue("an open-sky preference must exist",
                scanner.contains("canBlockSeeTheSky"));
    }

    @Test
    public void carryPlayerClimbsBeforeItFlies() throws Exception {
        String carry = read(SKILLS + "carry_player/code.js");
        assertTrue("a cruise altitude is computed",
                carry.contains("cruiseY"));
        assertTrue("the route is sampled for terrain peaks",
                carry.contains("getTopY("));
        assertTrue("the drone climbs straight up first",
                carry.contains("stage: 'climb'"));
        assertTrue("the landing is scanned before dismount",
                carry.contains("findSafeLanding("));
        assertTrue("the landing is re-checked after the descent",
                carry.contains("stage: 'final-check'"));
        assertTrue("a failure never abandons the rider mid-air",
                carry.contains("releaseSafely("));
        assertTrue("a stale entity id is re-resolved",
                carry.contains("disappeared before pickup"));
        assertTrue("an underground player gets a 2x2 shaft first",
                carry.contains("skills.call('escape_cave'"));
    }

    @Test
    public void transportEntityFollowsTheSameLandingRules() throws Exception {
        String transport = read(SKILLS + "transport_entity/code.js");
        assertTrue("the entity climb leg exists", transport.contains("stage: 'climb'"));
        assertTrue("the entity landing is scanned",
                transport.contains("findSafeLanding("));
        assertTrue("the entity is not abandoned mid-air",
                transport.contains("releaseSafely("));
        assertTrue("an underground destination gets a shaft",
                transport.contains("skills.call('escape_cave'"));
    }

    @Test
    public void caveEscapeIsWideEnoughForARider() throws Exception {
        String escape = read(SKILLS + "escape_cave/code.js");
        assertTrue("a 2x2 footprint must be declared",
                escape.contains("var cells = [[0, 0], [1, 0], [0, 1], [1, 1]]"));
        assertTrue("the shaft must reach open sky",
                escape.contains("topY"));
        assertTrue("a liquid layer must stop the shaft",
                escape.contains("block.isLiquid === true"));
        assertTrue("the layer is re-checked for falling fill",
                escape.contains("again.isAir !== true"));
        assertTrue("success requires reaching the goal height",
                escape.contains("reachedY"));
        assertTrue("the skill must not claim a 1-wide tunnel works",
                escape.contains("do not fly through a 1-wide hole"));
    }

    @Test
    public void navigationNeverTunnelsWithAPassenger() throws Exception {
        String guard = read(MAIN + "manager/navigation/impl/RiderGuard.java");
        assertTrue("a living rider is detected",
                guard.contains("riddenByEntity instanceof EntityLivingBase"));
        // Recovery lives in NavigationRecovery; the manager records the flag.
        String module = read(MAIN + "manager/navigation/impl/NavigationRecovery.java")
                + read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("recovery must consult the guard",
                module.contains("RiderGuard.blocksTunnel(session)"));
        assertTrue("the dig entry point must consult the guard",
                module.contains("if (RiderGuard.blocksTunnel(session)) return false;"));
        assertTrue("the session records the rider",
                module.contains("RiderGuard.isCarrying(controller.drone)"));
        String session = read(MAIN + "manager/navigation/impl/NavigationSession.java");
        assertTrue("the rider flag lives on the session",
                session.contains("boolean carryingRider;"));
    }

    @Test
    public void theSafeLandingToolIsPublished() throws Exception {
        String tools = read(MAIN + "harness/tools/NavigationTools.java");
        assertTrue("find_safe_landing must be declared",
                tools.contains("ToolSpec.builder(\"find_safe_landing\")"));
        assertTrue("find_safe_landing must be bound",
                tools.contains("registry.bind(\"find_safe_landing\""));
        String view = read(MAIN + "harness/tools/view/LandingView.java");
        assertTrue("the view returns hover coordinates",
                view.contains("hoverY"));
        assertTrue("the view reports movedFromRequest",
                view.contains("movedFromRequest"));
        String prompt = read("src/main/resources/prompts/system.md");
        assertTrue("the prompt must forbid dropping a rider", prompt.contains("find_safe_landing"));
        assertTrue("the prompt must require the 2x2 shaft", prompt.contains("escape_cave"));
        assertTrue("the prompt must say a rider is not cargo",
                prompt.contains("A rider is not cargo"));
    }

    @Test
    public void playerStateExposesCarryFacts() throws Exception {
        // The handler moved to PlayerContainerQueryTools; the facts are the
        // contract, not the file that prints them.
        String query = read(MAIN + "harness/tools/PlayerContainerQueryTools.java");
        assertTrue("get_player must return the entity id",
                query.contains("\"entityId\""));
        assertTrue("get_player must say whether the player sees sky",
                query.contains("canSeeSky"));
        assertTrue("get_player must flag an underground player",
                query.contains("\"underground\""));
        assertTrue("get_player must report an existing mount",
                query.contains("ridingEntityId"));
    }

    @Test
    public void escapeCaveIsBundled() throws Exception {
        assertTrue(new File(SKILLS + "escape_cave/code.js").isFile());
        assertTrue(new File(SKILLS + "escape_cave/SKILL.md").isFile());
        String index = read(SKILLS + "index.json");
        assertTrue("the skill must be indexed", index.contains("\"escape_cave\""));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
