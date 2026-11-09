package ir.layercloud.cortexdrone;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Structural guards for the "play fair" rules.
 *
 * <p>These are cheap source assertions for behaviours that unit tests cannot
 * exercise without a live world: container animation, reach filtering, damage
 * journalling and the detour-before-dig order.</p>
 */
public class LegitimacyGuardTest {
    private static final String MAIN = SourceTree.MAIN;
    private static final String NAV_MANAGER = SourceTree.MAIN + "manager/navigation/impl";

    @Test
    public void containerTransfersAnimateThroughTheRealInventoryHook() throws Exception {
        String animation = read(MAIN + "service/inventory/ContainerAnimation.java");
        assertTrue("containers must be opened through IInventory",
                animation.contains("inventory.openInventory()"));
        assertTrue("containers must be closed again",
                animation.contains("inventory.closeInventory()"));
    }

    @Test
    public void nearbyTransfersAreFilteredToReachableContainers() throws Exception {
        String service = read(MAIN + "service/inventory/InventoryService.java");
        assertTrue("withdraw must filter to reachable containers",
                service.contains("ContainerAccess.reachable"));
        assertFalse("withdraw must not search around the player",
                service.contains("ownerTiles"));
    }

    @Test
    public void navigationPrefersDetoursOverDigging() throws Exception {
        String manager = read(NAV_MANAGER + "/NavigationRecovery.java");
        int methodStart = manager.indexOf("void recover(");
        assertTrue("the recovery escalation not found", methodStart > 0);
        int methodEnd = manager.indexOf("\n    private boolean resumeOpenTunnel(", methodStart);
        assertTrue("recover() must end before resumeOpenTunnel()", methodEnd > methodStart);
        String recover = manager.substring(methodStart, methodEnd);

        int detour = recover.indexOf("nextDetour(");
        int dig = recover.indexOf("tryTunnel(");
        assertTrue("recover() must look for a detour", detour > 0);
        assertTrue("recover() must be able to dig as a last resort", dig > 0);
        assertTrue("a detour must be tried before tunnelling", detour < dig);
        String recovery = read(MAIN + "manager/navigation/impl/TunnelRecovery.java");
        assertTrue("the escalation must aim at diggable rock",
                recovery.contains("TunnelPlanner.nextObstruction("));
        assertTrue("the escalation must include the vertical step",
                recovery.contains("TunnelPlanner.nextVertical("));
    }

    @Test
    public void navigationJournalsWhatItBreaks() throws Exception {
        // The journal call may live in NavigationManager or in the TunnelDigger
        // choke point every dig passes through; it must exist in exactly the
        // path that actually breaks a block.
        String manager = SourceTree.readPackage(NAV_MANAGER);
        String digger = read(MAIN + "manager/navigation/impl/TunnelDigger.java");
        assertTrue("auto-dig must be journalled for repair_trail",
                manager.contains("getDamageJournal().record(")
                        || digger.contains("getDamageJournal().record("));
    }

    @Test
    public void repairTrailIsExposedAsATool() throws Exception {
        String tools = read(MAIN + "harness/tools/BlockTools.java");
        assertTrue("repair_trail must be a tool", tools.contains("\"repair_trail\""));
        String catalog = read(MAIN + "harness/tool/ToolCatalog.java");
        assertTrue("BlockTools must be in the catalog", catalog.contains("BlockTools"));
    }

    @Test
    public void reachedPlacesAreRememberedWithTheirTrail() throws Exception {
        String memory = read(MAIN + "harness/tools/PlaceMemory.java");
        assertTrue("visits must be stored as machine memory", memory.contains("\"machine\""));
        assertTrue("the approach trail must be stored", memory.contains("snapshot(controller"));
    }

    private static String read(String path) {
        return SourceTree.read(path);
    }
}
