package ir.layercloud.cortexdrone;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Structural guards for the block and transfer feedback.
 *
 * <p>These behaviours need a live world to see, so the tests pin the call sites
 * that make them happen:</p>
 * <ul>
 *   <li>placing goes through {@link ir.layercloud.cortexdrone.service.world.BlockPlacer},
 *       which runs {@code onBlockPlacedBy} and refreshes the client;</li>
 *   <li>placing must not use the break effect ({@code playAuxSFX(2001)});</li>
 *   <li>every mutation refreshes the block so the client stops showing the old
 *       state;</li>
 *   <li>transfers start an item trail.</li>
 * </ul>
 */
public class FeedbackGuardTest {
    private static final String MAIN = SourceTree.MAIN;

    @Test
    public void placingRunsThePlayerPlacementHooksAndNotTheBreakEffect() throws Exception {
        String place = read(MAIN + "action/block/impl/PlaceBlockAction.java");
        assertTrue("place_block must use the shared placer", place.contains("BlockPlacer.place("));
        assertFalse("the break effect on a placement is wrong",
                place.contains("playAuxSFX(2001"));
        assertTrue("the placer must run onBlockPlacedBy",
                read(MAIN + "service/world/BlockPlacer.java").contains("onBlockPlacedBy"));
        assertTrue("the placer must refresh the client",
                read(MAIN + "service/world/BlockPlacer.java").contains("markBlockForUpdate"));
    }

    @Test
    public void replaceAndRestoreRefreshTheWorld() throws Exception {
        assertTrue(read(MAIN + "action/block/impl/ReplaceBlockAction.java")
                .contains("BlockPlacer.place("));
        assertTrue(read(MAIN + "action/block/impl/RestoreBlocksAction.java")
                .contains("BlockPlacer.refresh("));
        assertTrue(read(MAIN + "action/block/impl/BreakBlockAction.java")
                .contains("BlockPlacer.refresh("));
    }

    @Test
    public void containerTransfersStartAnItemTrail() throws Exception {
        String animation = read(MAIN + "service/inventory/TransferAnimation.java");
        assertTrue("item movement must show the real item icon",
                animation.contains("iconcrack_"));
        assertTrue(read(MAIN + "service/inventory/InventoryService.java")
                .contains("TransferAnimation."));
        assertTrue(read(MAIN + "action/inventory/impl/MoveSlotAction.java")
                .contains("TransferAnimation."));
        assertTrue(read(MAIN + "action/inventory/impl/WithdrawChestAction.java")
                .contains("TransferAnimation."));
    }

    @Test
    public void animationQueuesAreAdvancedByTheServerTick() throws Exception {
        String manager = read(MAIN + "AIDroneManager.java");
        assertTrue(manager.contains("ContainerCloser.tick()"));
        assertTrue(manager.contains("TransferAnimation.tick()"));
    }

    private static String read(String path) {
        return SourceTree.read(path);
    }
}
