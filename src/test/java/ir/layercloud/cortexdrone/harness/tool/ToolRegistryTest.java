package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tool-surface invariants for the registry-driven harness.
 *
 * <p>Every tool is declared exactly once, has a real description and schema,
 * and the removed catch-all routers/composite tasks stay removed.</p>
 */
public class ToolRegistryTest {
    private static final Set<String> REMOVED = new HashSet<>(Arrays.asList(
            "inspect", "visuals", "skills", "transfer",
            "queue_mine", "queue_build", "mine_filter", "mine_rect", "mine_box",
            "gather_distant", "hunt", "farm", "chop", "build_structure",
            "replace_room", "replace_box", "replace_blocks", "sort_chests",
            "transfer_containers", "machine_craft", "cook", "smelt", "mine", "carry"
    ));

    @Test
    public void catalogDeclaresEveryToolExactlyOnce() {
        List<ToolSpec> specs = ToolCatalog.standard();
        Set<String> names = new HashSet<>();
        for (ToolSpec spec : specs) {
            assertTrue("Duplicate tool: " + spec.getName(), names.add(spec.getName()));
            assertFalse("Missing description: " + spec.getName(), spec.getDescription().trim().isEmpty());
            assertEquals("Not an object schema: " + spec.getName(),
                    "object", spec.getParameters().get("type").getAsString());
            assertTrue("Missing properties object: " + spec.getName(),
                    spec.getParameters().has("properties")
                            && spec.getParameters().get("properties").isJsonObject());
        }
        assertEquals("the published tool surface must not shrink", 75, names.size());
    }

    @Test
    public void removedRoutersAndCompositeTasksStayRemoved() {
        Set<String> names = new HashSet<>();
        for (ToolSpec spec : ToolCatalog.standard()) names.add(spec.getName());
        for (String removed : REMOVED) {
            assertFalse("Legacy tool returned: " + removed, names.contains(removed));
        }
    }

    @Test
    public void requiredPrimitivesAndHarnessToolsExist() {
        Set<String> names = new HashSet<>();
        for (ToolSpec spec : ToolCatalog.standard()) names.add(spec.getName());
        for (String required : Arrays.asList(
                "state", "cancel", "goto", "stop", "get_breadcrumbs",
                "break_block", "place_block", "replace_block", "repair_trail",
                "interact_block", "use_item", "attack_entity", "pick_entity", "unpick_entity",
                "get_entity_health", "attack_plan",
                "move_item", "give_to_player", "take_from_player", "withdraw_chest",
                "deposit_chest", "drop_items", "suck_items", "craft",
                "set_led", "set_light", "spawn_particles", "play_sound", "play_note",
                "say", "set_status_text", "ask_player",
                "execute", "lesson", "memory", "skill", "skill_run", "skill_refine",
                "web_search", "web_fetch", "wiki_lookup",
                "scan_blocks", "poll_scan",
                "oc_state", "oc_methods", "oc_call", "oc_machine",
                "find_build_site", "survey_build_site", "plan_structure",
                "find_oredict")) {
            assertTrue("Missing tool: " + required, names.contains(required));
        }
    }

    @Test
    public void registryRejectsDuplicateAndUnboundTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(spec("state"));
        try {
            registry.declare(spec("STATE"));
            fail("duplicate declaration must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("state"));
        }
        try {
            registry.bind("missing", "test", (context, args) -> new JsonObject());
            fail("binding an undeclared tool must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
        try {
            registry.requireAllBound();
            fail("unbound declarations must fail startup validation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("state"));
        }
    }

    @Test
    public void bindingExposesToolToModelAndJsSurface() {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(spec("echo"));
        registry.bind("echo", "test", (context, args) -> {
            JsonObject result = ToolJson.ok();
            result.addProperty("echo", args.get("value").getAsString());
            return result;
        });
        registry.requireAllBound();
        assertEquals(1, registry.tools().size());
        assertEquals(1, registry.toOpenAiTools().size());
        assertNotNull(registry.find("echo"));
        assertEquals("echo", registry.toOpenAiTools().get(0).getAsJsonObject()
                .getAsJsonObject("function").get("name").getAsString());
    }

    @Test
    public void internalToolsAreHiddenFromTheModelButCallableInJs() {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(ToolSpec.builder("internal_probe")
                .description("internal").parameters(ToolSchema.object())
                .affinity(ToolAffinity.ASYNC).internal().build());
        registry.bind("internal_probe", "test", (context, args) -> ToolJson.ok());
        assertEquals(1, registry.tools().size());
        assertEquals(0, registry.modelTools().size());
    }

    private static ToolSpec spec(String name) {
        return ToolSpec.builder(name)
                .description("Test tool " + name + ".")
                .parameters(ToolSchema.object().requiredString("value", "Value."))
                .affinity(ToolAffinity.ASYNC)
                .build();
    }
}
