package ir.layercloud.cortexdrone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Drone status formatting must stay within the drone display and never NPE.
 */
public class DroneStatusRendererTest {
    @Test
    public void statusIsTrimmedToTheDisplayWidth() {
        assertEquals("abcdefghij", DroneStatusRenderer.trimStatus("abcdefghijklmnop"));
        assertEquals("short", DroneStatusRenderer.trimStatus("short"));
    }

    @Test
    public void nullStatusIsEmpty() {
        assertEquals("", DroneStatusRenderer.trimStatus(null));
    }

    @Test
    public void sayFallsBackToAGenericName() {
        String line = DroneStatusRenderer.say(null, "hello");
        assertTrue(line.contains("[Drone:AI]"));
        assertTrue(line.contains("hello"));
    }

    @Test
    public void sayUsesTheDroneNameWhenKnown() {
        assertTrue(DroneStatusRenderer.say("Miner", "hi").contains("[Drone:Miner]"));
    }

    @Test
    public void renderingIsSafeWithoutADrone() {
        DroneStatusRenderer.render(null, null, null, null, null);
        DroneStatusRenderer.render(null, DroneController.forTest(1, "x", "owner"), null, null, null);
    }
}
