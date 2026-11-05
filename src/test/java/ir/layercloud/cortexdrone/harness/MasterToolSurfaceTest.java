package ir.layercloud.cortexdrone.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class MasterToolSurfaceTest {
    @Test
    public void onlyMasterPrimitivesAreRegistered() {
        Set<String> actual = new HashSet<String>(Arrays.asList(MasterToolCatalog.METHODS));
        Set<String> expected = new HashSet<String>(Arrays.asList(
            "state", "inspect", "navigate", "tunnel", "digup", "digdown",
            "break_block", "place_block", "interact_block", "use_item",
            "transfer", "craft", "effect", "skills", "cancel"
        ));
        assertEquals(expected, actual);
    }

    @Test
    public void legacyCompositeToolsAreGone() {
        Set<String> actual = new HashSet<String>(Arrays.asList(MasterToolCatalog.METHODS));
        for (String legacy : MasterToolCatalog.REMOVED_COMPOSITE_TOOLS) {
            assertFalse("Legacy tool still exposed: " + legacy, actual.contains(legacy));
        }
    }

    @Test
    public void masterToolNamesAreUnique() {
        Set<String> actual = new HashSet<String>();
        for (String method : MasterToolCatalog.METHODS) {
            assertTrue("Duplicate master tool: " + method, actual.add(method));
        }
    }
}
