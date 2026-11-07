package ir.layercloud.cortexdrone;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Structural guards for the "play fair" rules.
 *
 * <p>These are cheap source assertions for behaviours that unit tests cannot
 * exercise without a live world: container animation, reach filtering, damage
 * journalling and the detour-before-dig order.</p>
 */
public class LegitimacyGuardTest {
    private static final String MAIN = "src/main/java/ir/layercloud/cortexdrone/";

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
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        int methodStart = manager.indexOf("private void recover(");
        assertTrue("recover() not found", methodStart > 0);
        int methodEnd = manager.indexOf("\n    }", methodStart);
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
        String manager = read(MAIN + "manager/navigation/impl/NavigationManager.java");
        assertTrue("auto-dig must be journalled for repair_trail",
                manager.contains("getDamageJournal().record("));
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

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
