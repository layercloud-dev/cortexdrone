package ir.layercloud.cortexdrone;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Vein mode must stay a single physical break, not an area clear.
 *
 * <p>These guards live at the source level because the behaviour needs a live
 * world: the pure search is covered by {@code VeinFloodTest}, and the schema by
 * {@code BlockToolsVeinSchemaTest}.</p>
 */
public class VeinMiningRegressionTest {
    private static final String MAIN = SourceTree.MAIN;

    @Test
    public void veinModeIsBoundedAndOptIn() {
        String request = SourceTree.require(MAIN + "action/block/BlockRequest.java");
        assertTrue("vein mode must be a request flag", request.contains("boolean vein;"));
        assertTrue("the radius must be part of the request", request.contains("int veinRadius;"));
        assertTrue("the cap must be part of the request", request.contains("int veinBlocks;"));

        String limits = SourceTree.require(MAIN + "service/world/VeinLimits.java");
        assertTrue("both bounds are clamped, never trusted raw",
                limits.contains("clampRadius") && limits.contains("clampBlocks"));
        assertTrue("the cap must stay finite", limits.contains("MAX_BLOCKS"));
    }

    @Test
    public void veinFillIsFaceConnectedAndPure() {
        String flood = SourceTree.require(MAIN + "service/world/VeinFlood.java");
        assertTrue("a diagonal neighbour must not join the vein",
                flood.contains("int[][] FACES"));
        assertFalse("the fill must not do 26-neighbour clustering",
                flood.contains("dx = -1; dx <= 1"));
        assertTrue("the fill is world-free so it can be tested",
                !flood.contains("import net.minecraft.world.World"));

        String blocks = SourceTree.require(MAIN + "service/world/VeinBlocks.java");
        assertTrue("same block and metadata define one vein",
                blocks.contains("world.getBlockMetadata(cx, cy, cz) == meta"));
        assertTrue("every vein block obeys the normal mineable rules",
                blocks.contains("ItemClassifier.isMineableBlock(world, cx, cy, cz)"));
    }

    @Test
    public void protectedZonesCoverThePlannedVein() {
        String tools = SourceTree.require(MAIN + "harness/tools/BlockTools.java");
        assertTrue("a vein that reaches a protected zone must be refused",
                tools.contains("veinZone("));
        assertTrue("the check must run over the planned blocks, not only the target",
                tools.contains("VeinBlocks.plan("));
        assertTrue("confirm_zone_break remains the only override",
                tools.contains("confirm_zone_break"));
    }
    @Test
    public void theBreakActionReportsWhatItActuallyRemoved() {
        String action = SourceTree.require(MAIN + "action/block/impl/BreakBlockAction.java");
        assertTrue("vein mode must exist in the action", action.contains("breakVein("));
        assertTrue("a partial vein must be reported with its real count",
                action.contains("BlockResult.vein("));
        assertTrue("the cap must reach the planner",
                action.contains("getVeinBlocks()"));
    }

    @Test
    public void theModelIsToldVeinMiningExists() {
        String prompt = SourceTree.read(SourceTree.RESOURCES + "prompts/system.md");
        assertTrue("the system prompt documents vein mode",
                prompt.contains("break_block({x, y, z, vein:true})"));
        assertTrue("the prompt documents both bounds",
                prompt.contains("vein_radius") && prompt.contains("max_vein_blocks"));
    }
}
