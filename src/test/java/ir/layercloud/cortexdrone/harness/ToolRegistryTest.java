package ir.layercloud.cortexdrone.harness;

import ir.layercloud.cortexdrone.harness.rpc.impl.BlockRpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.impl.InteractionRpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.impl.InventoryActionRpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.impl.NavigationRpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.impl.ScanRpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.impl.SessionRpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.impl.SkillRpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.impl.VisualsRpcHandler;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tool-surface invariants.
 *
 * <p>Every tool belongs to exactly one domain handler and {@link ToolRegistry}
 * rejects duplicates. These tests also pin the explicit primitive set: the old
 * catch-all routers ({@code inspect}, {@code visuals}, {@code skills},
 * {@code transfer}) and the removed composite tasks must never return.</p>
 */
public class ToolRegistryTest {
    private static final Set<String> REMOVED_ROUTER_TOOLS = new HashSet<>(Arrays.asList(
            "inspect", "visuals", "skills", "transfer"
    ));

    private static final Set<String> REMOVED_COMPOSITE_TOOLS = new HashSet<>(Arrays.asList(
            "queue_mine", "queue_build", "mine_filter", "mine_rect", "mine_box",
            "gather_distant", "hunt", "farm", "chop", "build_structure",
            "replace_room", "replace_box", "replace_blocks", "sort_chests",
            "transfer_containers", "machine_craft", "cook", "smelt", "mine", "carry"
    ));

    private static ToolRegistry[] handlers() {
        return new ToolRegistry[]{
                SessionRpcHandler.tools(),
                NavigationRpcHandler.tools(),
                BlockRpcHandler.tools(),
                InteractionRpcHandler.tools(),
                InventoryActionRpcHandler.tools(),
                VisualsRpcHandler.tools(),
                SkillRpcHandler.tools(),
                ScanRpcHandler.tools()
        };
    }

    @Test
    public void registeringTheSameNameTwiceIsRejected() {
        ToolRegistry registry = new ToolRegistry().register("state", "first");
        try {
            registry.register("STATE", "second");
            fail("duplicate registration must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("state"));
        }
        assertEquals(1, registry.size());
        assertEquals("first", registry.find("state").getDescription());
    }

    @Test
    public void blankNamesAreRejected() {
        try {
            new ToolRegistry().register("   ", "blank");
            fail("blank tool name must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("blank"));
        }
    }

    @Test
    public void lookupsAreNormalized() {
        ToolRegistry registry = new ToolRegistry().register("  Break_Block ", "one block");
        assertTrue(registry.contains("BREAK_BLOCK"));
        assertTrue(registry.contains("break_block"));
        assertNull(registry.find("missing"));
    }

    @Test
    public void everyHandlerOwnsItsTools() {
        for (ToolRegistry registry : handlers()) {
            assertTrue("Handler exposes no tools", registry.size() > 0);
            for (ToolRegistry.Tool tool : registry.tools()) {
                assertFalse("Missing description: " + tool.getName(),
                        tool.getDescription().trim().isEmpty());
            }
        }
    }

    @Test
    public void toolNamesAreUniqueAcrossAllHandlers() {
        Set<String> seen = new HashSet<>();
        for (ToolRegistry registry : handlers()) {
            for (String name : registry.names()) {
                assertTrue("Tool owned by two handlers: " + name, seen.add(name));
            }
        }
    }

    @Test
    public void routerToolsAreGone() {
        Set<String> all = new HashSet<>();
        for (ToolRegistry registry : handlers()) all.addAll(Arrays.asList(registry.names()));
        for (String router : REMOVED_ROUTER_TOOLS) {
            assertFalse("Catch-all router must stay removed: " + router, all.contains(router));
        }
    }

    @Test
    public void removedCompositeToolsStayRemoved() {
        Set<String> all = new HashSet<>();
        for (ToolRegistry registry : handlers()) all.addAll(Arrays.asList(registry.names()));
        for (String legacy : REMOVED_COMPOSITE_TOOLS) {
            assertFalse("Legacy composite tool still exposed: " + legacy, all.contains(legacy));
        }
    }

    @Test
    public void primitiveOperationsAreRegisteredExplicitly() {
        Set<String> all = new HashSet<>();
        for (ToolRegistry registry : handlers()) all.addAll(Arrays.asList(registry.names()));
        Set<String> required = new HashSet<>(Arrays.asList(
                "state", "cancel", "goto", "stop", "get_breadcrumbs",
                "break_block", "place_block",
                "interact_block", "use_item", "attack_entity", "pick_entity", "unpick_entity",
                "move_item", "give_to_player", "take_from_player", "withdraw_chest",
                "deposit_chest", "drop_items", "suck_items", "craft",
                "set_led", "set_light", "spawn_particles", "play_sound", "play_note",
                "say", "set_status_text",
                "search_skills", "list_skills", "save_skill",
                "poll_scan"
        ));
        for (String tool : required) {
            assertTrue("Missing tool: " + tool, all.contains(tool));
        }
    }
}
