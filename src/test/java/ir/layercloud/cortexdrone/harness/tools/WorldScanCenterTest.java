package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Every scan/radius must be centred on the drone, not the player.
 *
 * <p>These assertions guard the contract that made scans return "zero blocks"
 * when the player stood farther than the drone's loaded chunks: the centre and
 * the wording both have to say drone.</p>
 */
public class WorldScanCenterTest {
    private static JsonObject schema(String tool) {
        for (ir.layercloud.cortexdrone.harness.tool.ToolSpec spec : WorldTools.specs()) {
            if (spec.getName().equals(tool)) return spec.getParameters();
        }
        return null;
    }

    @Test
    public void scanToolsAdvertiseTheDroneAsTheDefaultCenter() {
        for (String tool : new String[]{"scan_room", "scan_blocks", "scan_veins", "get_entities"}) {
            JsonObject params = schema(tool);
            assertNotNull(tool + " has no schema", params);
            String json = params.toString();
            assertTrue(tool + " does not mention the drone: " + json, json.contains("drone"));
            assertFalse(tool + " still says player default: " + json,
                    json.contains("defaults to the player")
                            || json.contains("defaults to the task player"));
        }
    }

    @Test
    public void scansAcceptAnExplicitPlayerAnchor() {
        for (String tool : new String[]{"scan_room", "scan_blocks", "scan_veins", "get_entities"}) {
            JsonObject params = schema(tool);
            String json = params.toString();
            assertTrue(tool + " has no anchor option: " + json, json.contains("anchor"));
            assertTrue(tool + " anchor lacks player: " + json, json.contains("player"));
        }
    }

    @Test
    public void scanBlocksExposesAdvancedOptions() {
        JsonObject params = schema("scan_blocks");
        assertNotNull(params);
        String json = params.toString();
        for (String option : new String[]{
                "shape", "sphere", "cylinder", "shell",
                "limit", "include_air", "min_y", "max_y", "anchor"}) {
            assertTrue("scan_blocks is missing " + option, json.contains(option));
        }
    }

    @Test
    public void findDistantSearchesOutwardFromTheDrone() {
        String description = null;
        for (ir.layercloud.cortexdrone.harness.tool.ToolSpec spec : WorldTools.specs()) {
            if (spec.getName().equals("find_distant")) description = spec.getDescription();
        }
        assertNotNull(description);
        assertTrue(description, description.contains("DRONE"));
    }

    @Test
    public void radiusBoundsStaySane() {
        JsonObject params = schema("scan_blocks");
        String json = params.toString();
        // Guards against a later edit widening the scan into a server stall.
        assertTrue(json.contains("\"maximum\":64"));
        assertTrue(json.contains("\"maximum\":2000"));
    }
}
