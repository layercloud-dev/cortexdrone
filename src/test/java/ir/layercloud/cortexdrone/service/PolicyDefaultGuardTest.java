package ir.layercloud.cortexdrone.service;

import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import ir.layercloud.cortexdrone.service.world.ScanLimits;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Duplicated defaults must come from their policy owner.
 *
 * <p>Regression: the dig allowance {@code 128/512} and the transfer default
 * {@code 512} were repeated in the tool schema, the argument view and the
 * inventory actions. Changing one left the others stale.</p>
 */
public class PolicyDefaultGuardTest {
    private static final String ROOT = "src/main/java/ir/layercloud/cortexdrone/";

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(ROOT + path)), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new AssertionError("cannot read " + path, failure);
        }
    }

    @Test
    public void navigationDigDefaultsComeFromNavigationLimits() {
        assertTrue("the schema default must read NavigationLimits",
                read("harness/tools/NavigationTools.java").contains("NavigationLimits.DEFAULT_DIG_BLOCKS"));
        assertTrue("the view default must read NavigationLimits",
                read("harness/tools/view/NavigationView.java").contains("NavigationLimits.DEFAULT_DIG_BLOCKS"));
    }

    @Test
    public void transferDefaultsComeFromInventoryLimits() {
        String[] callSites = {
                "action/inventory/impl/TakeFromPlayerAction.java",
                "action/inventory/impl/GiveToPlayerAction.java",
                "ReconnectHost.java"
        };
        for (String callSite : callSites) {
            assertTrue(callSite + " must read InventoryLimits.DEFAULT_TRANSFER_COUNT",
                    read(callSite).contains("InventoryLimits.DEFAULT_TRANSFER_COUNT"));
        }
    }

    @Test
    public void navigationWaitDefaultsComeFromNavigationLimits() {
        String tools = read("harness/tools/NavigationTools.java");
        assertTrue("the wait schema minimum must read NavigationLimits",
                tools.contains("NavigationLimits.MIN_WAIT_MS"));
        assertTrue("the wait schema maximum must read NavigationLimits",
                tools.contains("NavigationLimits.MAX_WAIT_MS"));
        assertTrue("the wait schema default must read NavigationLimits",
                tools.contains("NavigationLimits.DEFAULT_WAIT_MS"));

        String invoker = read("harness/tool/ToolInvoker.java");
        assertTrue("the wait budget must read NavigationLimits.DEFAULT_WAIT_MS",
                invoker.contains("NavigationLimits.DEFAULT_WAIT_MS"));
        assertTrue("the dig-budget hint must read NavigationLimits.MAX_DIG_BLOCKS",
                invoker.contains("NavigationLimits.MAX_DIG_BLOCKS"));
    }

    @Test
    public void theSessionDigDefaultComesFromNavigationLimits() {
        assertTrue("NavigationSession must read NavigationLimits.DEFAULT_DIG_BLOCKS",
                read("manager/navigation/impl/NavigationSession.java")
                        .contains("NavigationLimits.DEFAULT_DIG_BLOCKS"));
    }

    @Test
    public void navigationRecoveryDefaultsComeFromNavigationLimits() {
        String owner = "NavigationLimits.DEFAULT_RECOVERY_ATTEMPTS";
        assertTrue("NavigationTools must read the recovery default",
                read("harness/tools/NavigationTools.java").contains(owner));
        assertTrue("NavigationView must read the recovery default",
                read("harness/tools/view/NavigationView.java").contains(owner));
        assertTrue("NavigationSession must read the recovery default",
                read("manager/navigation/impl/NavigationSession.java").contains(owner));
    }

    @Test
    public void theFurnaceRadiusHasOneOwner() {
        assertEquals("the two service owners must agree",
                ScanLimits.MAX_FURNACE_RADIUS,
                InventoryLimits.MAX_FURNACE_RADIUS);
    }
}
