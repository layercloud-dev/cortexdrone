package ir.layercloud.cortexdrone.harness;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class MasterToolSurfaceTest {
    @Test
    public void onlyMasterPrimitivesAreRegistered() {
        Set<String> actual = new HashSet<>(Arrays.asList(MasterToolCatalog.METHODS));
        Set<String> expected = new HashSet<>(Arrays.asList(
                "state", "inspect", "goto", "stop",
                "break_block", "place_block", "interact_block", "use_item",
                "pick_entity", "unpick_entity",
                "transfer", "craft", "visuals", "skills", "cancel"
        ));
        assertEquals(expected, actual);
    }

    @Test
    public void legacyCompositeToolsAreGone() {
        Set<String> actual = new HashSet<>(Arrays.asList(MasterToolCatalog.METHODS));
        for (String legacy : MasterToolCatalog.REMOVED_COMPOSITE_TOOLS) {
            assertFalse("Legacy tool still exposed: " + legacy, actual.contains(legacy));
        }
    }

    @Test
    public void masterToolNamesAreUnique() {
        Set<String> actual = new HashSet<>();
        for (String method : MasterToolCatalog.METHODS) {
            assertTrue("Duplicate master tool: " + method, actual.add(method));
        }
    }
}
