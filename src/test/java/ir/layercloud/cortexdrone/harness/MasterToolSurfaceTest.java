package ir.layercloud.cortexdrone.harness;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class MasterToolSurfaceTest {
    private static final Set<String> REMOVED_COMPOSITE_TOOLS = new HashSet<>(Arrays.asList(
            "queue_mine", "queue_build", "mine_filter", "mine_rect", "mine_box",
            "gather_distant", "hunt", "farm", "chop", "build_structure",
            "replace_room", "replace_box", "replace_blocks", "sort_chests",
            "transfer_containers", "machine_craft", "cook", "smelt"
    ));

    @Test
    public void onlyMasterPrimitivesAreRegistered() {
        Set<String> actual = new HashSet<>(Arrays.asList(ToolRegistry.master().names()));
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
        ToolRegistry registry = ToolRegistry.master();
        for (String legacy : REMOVED_COMPOSITE_TOOLS) {
            assertFalse("Legacy tool still exposed: " + legacy, registry.contains(legacy));
        }
    }

    @Test
    public void masterToolNamesAreUnique() {
        Set<String> actual = new HashSet<>();
        for (String method : ToolRegistry.master().names()) {
            assertTrue("Duplicate master tool: " + method, actual.add(method));
        }
    }

    @Test
    public void everyToolHasADescription() {
        for (ToolRegistry.Tool tool : ToolRegistry.master().tools()) {
            assertFalse("Missing description: " + tool.getName(),
                    tool.getDescription().trim().isEmpty());
        }
    }

    @Test
    public void registrationIsNormalizedAndFirstWins() {
        ToolRegistry registry = new ToolRegistry()
                .register("  Inspect ", "first")
                .register("inspect", "second");
        assertEquals(1, registry.size());
        assertTrue(registry.contains("INSPECT"));
        assertEquals("first", registry.find(" inspect ").getDescription());
        assertNull(registry.find("missing"));
    }
}
