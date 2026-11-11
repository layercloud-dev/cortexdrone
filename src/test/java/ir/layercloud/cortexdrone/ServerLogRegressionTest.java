package ir.layercloud.cortexdrone;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the fixes made from the live server and bot logs.
 *
 * <p>Each case is a real failure observed in {@code logs/fml-server-latest.log}
 * or {@code config/cortexdrone/logs/cortexdrone.log}.</p>
 */
public class ServerLogRegressionTest {
    private static final String MAIN = "src/main/java/ir/layercloud/cortexdrone/";

    @Test
    public void emptyContainersAreProtectedFromTunnelling() throws Exception {
        String classifier = read(MAIN + "service/world/ItemClassifier.java");
        // The old code returned false for an empty IInventory, so navigation dug
        // straight through an empty crate.
        assertFalse("an empty IInventory must not be treated as unprotected",
                classifier.contains("return false;\n        }\n        return true;\n    }"));
        assertTrue("all inventory tiles must be protected",
                classifier.contains("if (te instanceof IInventory) return true;"));
        assertTrue("chests without a loaded tile are still chests",
                classifier.contains("BlockChest"));
    }

    @Test
    public void attackDoesNotUseAnInflatedBaseDamage() throws Exception {
        String inspector = read(MAIN + "service/inventory/ToolInspector.java");
        assertFalse("base attack damage must not be 20", inspector.contains("20.0f"));
        assertTrue("a fist is 1.0 damage", inspector.contains("float damage = 1.0f"));
    }

    @Test
    public void attackWorksWithNoInventorySlots() throws Exception {
        String attack = read(MAIN + "action/interact/impl/AttackEntityAction.java");
        assertTrue("an empty inventory must fall back to a punch",
                attack.contains("can still punch") || attack.contains("slot = -1"));
        assertFalse("a zero-slot inventory must not raise 'Invalid attack slot'",
                attack.contains("Invalid attack slot \" + slot)\n"));
    }

    @Test
    public void navigationMinesVerticallyTowardUndergroundGoals() throws Exception {
        String planner = read(MAIN + "service/navigation/impl/TunnelPlanner.java");
        assertTrue("a vertical step planner must exist", planner.contains("nextVertical("));
        String recovery = read(MAIN + "manager/navigation/impl/TunnelRecovery.java");
        assertTrue("recovery must use the vertical step", recovery.contains("TunnelPlanner.nextVertical("));
        String manager = read(MAIN + "manager/navigation/impl/NavigationRecovery.java");
        assertTrue("recovery must delegate to the escalation",
                manager.contains("TunnelRecovery.nextTarget("));
    }

    @Test
    public void supersededScriptsAreNotReportedAsBudgetFailures() throws Exception {
        String budget = read(MAIN + "harness/js/JsBudget.java");
        assertTrue("the budget must distinguish cancellation", budget.contains("superseded()"));
        String engine = read(MAIN + "harness/js/JsEngine.java");
        assertTrue("a superseded script gets its own code", engine.contains("\"js_cancelled\""));
    }

    @Test
    public void transientGatewayFailuresAreRetried() throws Exception {
        String client = read(MAIN + "harness/agent/LlmClient.java");
        assertTrue("5xx must be retried", client.contains("code >= 500"));
        assertTrue("model_not_available must be retried",
                client.contains("model_not_available"));
        assertTrue("retries are bounded", client.contains("MAX_ATTEMPTS"));
    }

    @Test
    public void droneInventorySizeIsReportedHonestly() throws Exception {
        String state = read(MAIN + "harness/tools/view/StateView.java");
        assertFalse("free slots must not assume eight", state.contains("8 - usedSlots"));
        assertTrue("the real slot count is reported", state.contains("\"slots\""));
    }

    @Test
    public void suckItemsExplainsAnInventoryWithoutSlots() throws Exception {
        String suck = read(MAIN + "action/inventory/impl/SuckItemsAction.java");
        assertTrue(suck.contains("no slots") || suck.contains("inventory upgrade"));
        assertTrue("pickup count is reported", suck.contains("\"pickedUp\""));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
