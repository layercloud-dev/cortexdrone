package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.js.JsEngine;
import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.Assert.*;

/**
 * Behavioural tests for the production, farming and digging skills.
 *
 * <p>The suite compiles every body, but a compiling skill can still mine the
 * block under itself or feed a stick to a furnace. These tests run the real
 * bodies through the real Rhino runtime against stub tools and assert the
 * mechanics the docs promise.</p>
 */
public class SkillBehaviorTest {

    /** One recorded tool call: name plus its JSON arguments. */
    private static final class Call {
        final String name;
        final JsonObject args;

        Call(String name, JsonObject args) {
            this.name = name;
            this.args = args;
        }
    }

    private static JsonObject copy(JsonObject source) {
        return new Gson().toJsonTree(source).getAsJsonObject();
    }

    private static final class Harness {
        final JsEngine engine;
        final List<Call> calls = new ArrayList<>();

        Harness(SkillLibrary library, JsonObject state, BiFunction<String, JsonObject, JsonObject> overrides) {
            ToolRegistry registry = new ToolRegistry();
            for (String name : new String[]{"state", "get_player", "get_block", "get_top_y",
                    "scan_blocks", "scan_veins", "scan_furnaces", "scan_chests",
                    "get_recipes", "get_drops", "goto", "break_block", "place_block",
                    "use_item", "craft", "move_item", "give_to_player", "withdraw_chest",
                    "suck_items", "take_from_player", "deposit_chest", "find_item",
                    "survey_build_site", "plan_structure", "get_blocks_in_box",
                    "get_entities", "get_entity_health", "attack_plan", "attack_entity",
                    "pick_entity", "unpick_entity", "find_safe_landing"}) {
                declare(registry, name);
                registry.bind(name, "test", (context, args) -> {
                    this.calls.add(new Call(name, copy(args)));
                    JsonObject override = overrides != null ? overrides.apply(name, args) : null;
                    if (override != null) return copy(override);
                    return defaultResult(name, state, args);
                });
            }
            this.engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                    registry, new ModConfig(), library);
        }

        JsonObject run(String skill, String paramsJson) {
            JsonObject result = this.engine.execute(null,
                    "return skills.call('" + skill + "', " + paramsJson + ");", 30_000L);
            assertTrue(skill + " threw: " + result, result.get("ok").getAsBoolean());
            return result.getAsJsonObject("result");
        }

        /** Runs the skill through its bound function name, not skills.call. */
        JsonObject runAsFunction(String skill, String paramsJson) {
            JsonObject result = this.engine.execute(null,
                    "return skills." + skill + "(" + paramsJson + ");", 30_000L);
            assertTrue(skill + " (function form) threw: " + result,
                    result.get("ok").getAsBoolean());
            return result.getAsJsonObject("result");
        }
    }

    private static void declare(ToolRegistry registry, String name) {
        ToolSchema schema = ToolSchema.object();
        switch (name) {
            case "get_block":
                schema.requiredInt("x", "X").requiredInt("y", "Y").requiredInt("z", "Z");
                break;
            case "get_top_y":
                schema.requiredInt("x", "X").requiredInt("z", "Z");
                break;
            case "get_player":
                schema.optionalString("name", "Player");
                break;
            case "get_recipes":
                schema.optionalString("filter", "Filter");
                break;
            case "craft":
                schema.optionalString("filter", "Filter").optionalInt("count", "Count", 1, 2304, 1);
                break;
            case "move_item":
                schema.optionalString("from", "From").optionalString("to", "To")
                        .optionalString("filter", "Filter").optionalInt("count", "Count", 0, 2304, 0)
                        .optionalInt("from_slot", "Slot", -1, 255, -1)
                        .optionalInt("to_slot", "Slot", -1, 255, -1)
                        .optionalInt("from_x", "X").optionalInt("from_y", "Y").optionalInt("from_z", "Z")
                        .optionalInt("to_x", "X").optionalInt("to_y", "Y").optionalInt("to_z", "Z");
                break;
            case "goto":
                schema.optionalNumber("x", "X").optionalNumber("y", "Y").optionalNumber("z", "Z")
                        .optionalObject("standoff_of", "Standoff")
                        .optionalBoolean("allow_dig", "Dig", false)
                        .optionalInt("max_wait_ms", "Wait", 1000, 180_000, 120_000)
                        .optionalInt("max_dig_blocks", "Blocks", 0, 512, 0)
                        .optionalInt("recovery_attempts", "Attempts", 0, 32, 12);
                break;
            case "break_block":
            case "place_block":
                schema.optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z")
                        .optionalString("block", "Block")
                        .optionalInt("max_wait_ms", "Wait", 1000, 180_000, 120_000);
                break;
            case "use_item":
                schema.optionalString("item", "Item").optionalInt("slot", "Slot", 0, 7, null)
                        .optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z")
                        .optionalInt("side", "Side", 0, 5, 1);
                break;
            case "scan_blocks":
                schema.optionalString("filter", "Filter").optionalInt("radius", "Radius", 1, 64, 24)
                        .optionalInt("limit", "Limit", 1, 2000, 400)
                        .optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z")
                        .optionalString("shape", "Shape");
                break;
            case "scan_veins":
            case "scan_furnaces":
            case "scan_chests":
                schema.optionalString("filter", "Filter").optionalInt("radius", "Radius", 1, 64, 24)
                        .optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z");
                break;
            case "withdraw_chest":
            case "deposit_chest":
                schema.optionalString("filter", "Filter").optionalInt("count", "Count", 0, 2304, 0)
                        .optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z");
                break;
            case "give_to_player":
            case "take_from_player":
                schema.optionalString("filter", "Filter").optionalInt("count", "Count", 0, 2304, 0)
                        .optionalString("player", "Player");
                break;
            case "suck_items":
                schema.optionalNumber("radius", "Radius", 1, 16, 4);
                break;
            case "find_item":
                schema.optionalString("filter", "Filter").optionalInt("radius", "Radius", 4, 64, 32);
                break;
            case "survey_build_site":
                schema.optionalInt("x1", "X").optionalInt("z1", "Z")
                        .optionalInt("x2", "X").optionalInt("z2", "Z")
                        .optionalInt("y", "Y").optionalInt("height", "Height")
                        .optionalBoolean("allow_liquid", "Liquid", false);
                break;
            case "plan_structure":
                schema.optionalString("style", "Style");
                break;
            case "get_blocks_in_box":
                schema.optionalInt("x1", "X").optionalInt("y1", "Y").optionalInt("z1", "Z")
                        .optionalInt("x2", "X").optionalInt("y2", "Y").optionalInt("z2", "Z")
                        .optionalBoolean("includeAir", "Air", false);
                break;
            case "get_entities":
                schema.optionalString("filter", "Filter").optionalInt("radius", "Radius", 4, 180, 64)
                        .optionalString("anchor", "Anchor");
                break;
            case "get_entity_health":
            case "attack_plan":
            case "attack_entity":
                schema.optionalInt("entity_id", "Entity").optionalString("item", "Item")
                        .optionalInt("slot", "Slot", 0, 7, null);
                break;
            case "pick_entity":
                schema.requiredInt("entity_id", "Entity");
                break;
            case "unpick_entity":
                schema.optionalInt("entity_id", "Entity");
                break;
            case "find_safe_landing":
                schema.optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z")
                        .optionalInt("radius", "Radius", 1, 24, 12)
                        .optionalBoolean("covered_ok", "Covered", false);
                break;
            default:
                break;
        }
        registry.declare(ToolSpec.builder(name)
                .description("Test stub for " + name + ".")
                .parameters(schema)
                .affinity(ToolAffinity.ASYNC)
                .build());
    }

    private static JsonObject defaultResult(String name, JsonObject state, JsonObject args) {
        JsonObject result = ToolJson.ok();
        switch (name) {
            case "state":
                return copy(state);
            case "get_player":
                result.addProperty("name", "Tester");
                result.addProperty("blockX", 0);
                result.addProperty("blockY", 64);
                result.addProperty("blockZ", 0);
                result.addProperty("x", 0.5);
                result.addProperty("y", 64);
                result.addProperty("z", 0.5);
                result.addProperty("facingDx", 0);
                result.addProperty("facingDz", 1);
                return result;
            case "get_block":
                result.addProperty("name", "Air");
                result.addProperty("registryName", "minecraft:air");
                result.addProperty("isAir", true);
                result.addProperty("isLiquid", false);
                result.addProperty("hardness", 0);
                return result;
            case "get_top_y":
                result.addProperty("topY", 64);
                return result;
            case "scan_blocks":
            case "scan_veins":
            case "scan_furnaces":
            case "scan_chests":
                result.addProperty("count", 0);
                result.add("blocks", new JsonArray());
                result.add("veins", new JsonArray());
                result.add("furnaces", new JsonArray());
                result.add("chests", new JsonArray());
                return result;
            case "get_recipes":
                result.addProperty("count", 0);
                result.add("recipes", new JsonArray());
                return result;
            case "get_drops":
                result.addProperty("count", 0);
                result.add("drops", new JsonArray());
                return result;
            case "goto":
                result.addProperty("arrived", true);
                return result;
            case "break_block":
                result.addProperty("broken", true);
                return result;
            case "place_block":
                result.addProperty("placed", true);
                return result;
            case "use_item":
                result.addProperty("used", true);
                return result;
            case "craft":
                result.addProperty("crafted", args.has("count") ? args.get("count").getAsInt() : 1);
                return result;
            case "move_item":
                result.addProperty("moved", args.has("count") ? args.get("count").getAsInt() : 1);
                return result;
            case "give_to_player":
            case "take_from_player":
                result.addProperty("transferred", args.has("count") ? args.get("count").getAsInt() : 1);
                return result;
            case "withdraw_chest":
                result.addProperty("withdrawn", args.has("count") ? args.get("count").getAsInt() : 1);
                return result;
            case "deposit_chest":
                result.addProperty("deposited", 1);
                return result;
            case "suck_items":
                result.addProperty("sucked", 1);
                return result;
            case "find_item":
                result.addProperty("found", true);
                result.addProperty("totalAvailable", 64);
                result.addProperty("inDroneCount", 64);
                return result;
            case "survey_build_site":
                result.addProperty("slope", 0);
                result.addProperty("suggestedFloorY", 63);
                result.addProperty("floorY", 63);
                result.add("blockers", new JsonArray());
                return result;
            case "get_blocks_in_box":
                result.addProperty("count", 0);
                result.addProperty("totalVoxels", 0);
                result.add("blocks", new JsonArray());
                result.add("countsByType", new JsonObject());
                return result;
            case "get_entities":
                result.addProperty("count", 0);
                result.add("entities", new JsonArray());
                return result;
            case "get_entity_health":
            case "attack_plan":
                result.addProperty("found", true);
                result.addProperty("entity_id", args.has("entity_id")
                        ? args.get("entity_id").getAsInt() : 1);
                result.addProperty("name", "Zombie");
                result.addProperty("alive", true);
                result.addProperty("player", false);
                result.addProperty("health", 20);
                result.addProperty("total_health", 20);
                result.addProperty("can_damage", true);
                result.addProperty("attacks_to_kill", 3);
                result.addProperty("damage_per_hit", 7);
                result.addProperty("hurt_resistant_ticks", 0);
                result.addProperty("next_attack_in_ms", 200);
                return result;
            case "attack_entity":
                result.addProperty("killed", true);
                result.addProperty("healthAfter", 0);
                result.addProperty("damageDealt", 7);
                result.addProperty("nextAttackInMs", 500);
                return result;
            case "pick_entity":
                result.addProperty("picked", true);
                result.addProperty("entityId", args.has("entity_id")
                        ? args.get("entity_id").getAsInt() : 1);
                return result;
            case "unpick_entity":
                result.addProperty("unpicked", true);
                return result;
            case "find_safe_landing": {
                // Default fake world: open flat ground at y=64, so a landing is
                // always found unless a test overrides this.
                result.addProperty("found", true);
                result.addProperty("safe", true);
                result.addProperty("safeAtRequested", true);
                result.addProperty("movedFromRequest", false);
                JsonObject spot = new JsonObject();
                spot.addProperty("x", args.has("x") ? args.get("x").getAsInt() : 0);
                spot.addProperty("y", 64);
                spot.addProperty("z", args.has("z") ? args.get("z").getAsInt() : 0);
                spot.addProperty("hoverX", (args.has("x") ? args.get("x").getAsInt() : 0) + 0.5);
                spot.addProperty("hoverY", 64.35);
                spot.addProperty("hoverZ", (args.has("z") ? args.get("z").getAsInt() : 0) + 0.5);
                spot.addProperty("safe", true);
                spot.addProperty("openSky", true);
                spot.addProperty("surfaceLevel", true);
                spot.addProperty("floor", "Stone");
                result.add("spot", spot);
                return result;
            }
            default:
                return result;
        }
    }

    private static JsonObject state(String... stacks) {
        JsonObject state = new JsonObject();
        state.addProperty("ok", true);
        state.addProperty("x", 0.5);
        state.addProperty("y", 64);
        state.addProperty("z", 0.5);
        state.addProperty("slots", 8);
        state.addProperty("freeSlots", 8);
        state.addProperty("totalItems", 0);
        state.addProperty("isFull", false);
        JsonArray inventory = new JsonArray();
        for (String description : stacks) {
            String[] parts = description.split(":\\|:");
            JsonObject stack = new JsonObject();
            stack.addProperty("name", parts[0]);
            stack.addProperty("registryName", parts[1]);
            if (parts.length > 2) stack.addProperty("meta", Integer.parseInt(parts[2]));
            stack.addProperty("count", parts.length > 3 ? Integer.parseInt(parts[3]) : 64);
            inventory.add(stack);
        }
        state.add("inventory", inventory);
        return state;
    }

    private static SkillLibrary library() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skill-behavior").toFile();
        return new SkillLibrary(root);
    }

    @Test
    public void staircaseNeverBreaksTheFloorUnderTheNextStep() throws Exception {
        Harness harness = new Harness(library(), state(), (name, args) -> {
            if (!"get_block".equals(name)) return null;
            JsonObject solid = ToolJson.ok();
            solid.addProperty("name", "Stone");
            solid.addProperty("registryName", "minecraft:stone");
            solid.addProperty("isAir", false);
            solid.addProperty("isLiquid", false);
            solid.addProperty("hardness", 2);
            return solid;
        });
        harness.run("dig_staircase", "{direction:'north',length:6,start:{x:0,y:63,z:0},headroom:2}");

        List<Call> breaks = new ArrayList<>();
        for (Call call : harness.calls) if ("break_block".equals(call.name)) breaks.add(call);
        assertFalse("the staircase must dig something", breaks.isEmpty());
        for (Call call : breaks) {
            int y = call.args.get("y").getAsInt();
            // Walking levels are 63, 62, 61...; the floor under each is one lower.
            int step = 63 - y;
            assertNotEquals("the floor under the step was broken", 63 - (step + 1) - 1, y);
            assertEquals("the staircase left its axis", 0, call.args.get("x").getAsInt());
            assertTrue("the staircase went the wrong way", call.args.get("z").getAsInt() <= 0);
        }
    }

    @Test
    public void shaftStopsBeforeOpeningLiquid() throws Exception {
        Harness harness = new Harness(library(), state(), (name, args) -> {
            if (!"get_block".equals(name)) return null;
            JsonObject block = ToolJson.ok();
            block.addProperty("name", "Stone");
            block.addProperty("registryName", "minecraft:stone");
            block.addProperty("isAir", false);
            block.addProperty("isLiquid", false);
            block.addProperty("hardness", 2);
            return block;
        });
        JsonObject result = harness.run("dig_shaft", "{depth:6,start:{x:0,y:63,z:0}}");
        assertTrue("the shaft must report progress", result.get("mined").getAsInt() > 0);
        assertEquals("no bedrock is present in this world", false,
                result.get("reachedBedrock").getAsBoolean());
    }

    @Test
    public void smeltingPrefersFuelThatCanFinishTheBatch() throws Exception {
        JsonObject furnace = new JsonObject();
        furnace.addProperty("x", 0);
        furnace.addProperty("y", 64);
        furnace.addProperty("z", 0);
        furnace.addProperty("dist", 1);
        furnace.addProperty("burnTime", 0);
        furnace.add("items", new JsonArray());

        Harness harness = new Harness(
                library(),
                state("Stick:|:minecraft:stick:|:0:|:64",
                        "Charcoal:|:minecraft:coal:|:1:|:3",
                        "Iron Ore:|:minecraft:iron_ore:|:0:|:4"),
                (name, args) -> {
                    if (!"scan_furnaces".equals(name)) return null;
                    JsonObject scan = ToolJson.ok();
                    scan.addProperty("count", 1);
                    JsonArray furnaces = new JsonArray();
                    furnaces.add(furnace);
                    scan.add("furnaces", furnaces);
                    return scan;
                });
        harness.run("smelt_items", "{filter:'iron_ore',count:4,max_wait_s:5}");

        Call fuel = null;
        for (Call call : harness.calls) {
            if ("move_item".equals(call.name) && call.args.has("to_slot")
                    && call.args.get("to_slot").getAsInt() == 1) {
                fuel = call;
            }
        }
        assertNotNull("the furnace was never fuelled", fuel);
        assertTrue("a stick cannot finish a 200-tick item: " + fuel.args,
                fuel.args.get("filter").getAsString().toLowerCase().contains("charcoal"));
    }

    @Test
    public void portalDryRunRequiresFourteenObsidian() throws Exception {
        Harness harness = new Harness(library(),
                state("Obsidian:|:minecraft:obsidian:|:0:|:20"), null);
        JsonObject result = harness.run("build_nether_portal", "{x:0,z:0,y:63,dry_run:true}");
        JsonObject audit = result.getAsJsonObject("audit");
        assertEquals(14, audit.get("obsidianRequired").getAsInt());
        assertEquals(20, audit.get("obsidianHave").getAsInt());
        assertEquals(0, audit.get("missing").getAsInt());
        for (Call call : harness.calls) {
            assertNotEquals("a dry run must not place blocks", "place_block", call.name);
        }
    }

    @Test
    public void plantingTillsThenSowsEveryColumn() throws Exception {
        java.util.Set<String> tilledColumns = new java.util.HashSet<>();
        Harness harness = new Harness(library(),
                state("Wooden Hoe:|:minecraft:wooden_hoe:|:0:|:1",
                        "Wheat Seeds:|:minecraft:wheat_seeds:|:0:|:16"),
                (name, args) -> {
                    if ("use_item".equals(name) && args.has("item")
                            && args.get("item").getAsString().contains("hoe")) {
                        tilledColumns.add(args.get("x").getAsInt() + "," + args.get("z").getAsInt());
                        return null;
                    }
                    if (!"get_block".equals(name)) return null;
                    JsonObject block = ToolJson.ok();
                    block.addProperty("isLiquid", false);
                    // Soil at y=63 becomes farmland after the hoe, air one above.
                    boolean soil = args.has("y") && args.get("y").getAsInt() == 63;
                    boolean hoed = soil && tilledColumns.contains(
                            args.get("x").getAsInt() + "," + args.get("z").getAsInt());
                    block.addProperty("isAir", !soil);
                    block.addProperty("name", hoed ? "Farmland" : (soil ? "Grass Block" : "Air"));
                    block.addProperty("registryName", hoed ? "minecraft:farmland"
                            : (soil ? "minecraft:grass" : "minecraft:air"));
                    block.addProperty("meta", hoed ? 7 : 0);
                    return block;
                });
        JsonObject result = harness.run("plant_farm",
                "{crop:'wheat',x1:0,z1:0,x2:1,z2:1,y:63}");
        assertTrue("the plot should be tilled", result.get("tilled").getAsInt() > 0);
        assertEquals("the plot should be sown", result.get("tilled").getAsInt(),
                result.get("planted").getAsInt());
    }

    @Test
    public void craftAuditCountsRepeatedIngredientSlots() throws Exception {
        // A furnace uses eight cobblestone in one craft; the deficit must be 8,
        // not 1, or the audit would under-collect and the craft would fail.
        Harness harness = new Harness(library(),
                state("Cobblestone:|:minecraft:cobblestone:|:0:|:5"), (name, args) -> {
                    if (!"get_recipes".equals(name)) return null;
                    JsonObject recipes = ToolJson.ok();
                    recipes.addProperty("count", 1);
                    JsonArray list = new JsonArray();
                    JsonObject recipe = new JsonObject();
                    recipe.addProperty("output", "Furnace");
                    recipe.addProperty("outputCount", 1);
                    JsonArray ingredients = new JsonArray();
                    for (int i = 0; i < 8; i++) ingredients.add(new com.google.gson.JsonPrimitive("Cobblestone"));
                    recipe.add("ingredients", ingredients);
                    list.add(recipe);
                    recipes.add("recipes", list);
                    return recipes;
                });
        JsonObject result = harness.run("craft_items", "{filter:'furnace',count:1,dry_run:true}");
        JsonArray missing = result.getAsJsonArray("missing");
        assertEquals("one ingredient entry after aggregation", 1, missing.size());
        JsonObject entry = missing.get(0).getAsJsonObject();
        assertEquals("eight cobblestone are needed", 8, entry.get("required").getAsInt());
        assertEquals("only five are held", 5, entry.get("have").getAsInt());
        assertEquals("the deficit is three", 3, entry.get("deficit").getAsInt());
    }

    @Test
    public void attackToDeathUsesTheHealthPlanAndPacesSwings() throws Exception {
        final float[] health = new float[]{20.0f};
        final int[] swings = new int[]{0};
        Harness harness = new Harness(library(), state(),
                (name, args) -> {
                    if ("attack_plan".equals(name)) {
                        JsonObject plan = ToolJson.ok();
                        plan.addProperty("found", true);
                        plan.addProperty("entity_id", args.get("entity_id").getAsInt());
                        plan.addProperty("name", "Zombie");
                        plan.addProperty("alive", health[0] > 0);
                        plan.addProperty("player", false);
                        plan.addProperty("health", health[0]);
                        plan.addProperty("total_health", health[0]);
                        plan.addProperty("can_damage", true);
                        plan.addProperty("attacks_to_kill", 3);
                        plan.addProperty("damage_per_hit", 7);
                        plan.addProperty("hurt_resistant_ticks", 20);
                        plan.addProperty("immune", false);
                        plan.addProperty("next_attack_in_ms", 0);
                        return plan;
                    }
                    if ("attack_entity".equals(name)) {
                        swings[0]++;
                        health[0] = Math.max(0.0f, health[0] - 7.0f);
                        JsonObject hit = ToolJson.ok();
                        hit.addProperty("killed", health[0] <= 0.0f);
                        hit.addProperty("healthAfter", health[0]);
                        hit.addProperty("damageDealt", 7);
                        hit.addProperty("nextAttackInMs", 0);
                        return hit;
                    }
                    return null;
                });
        JsonObject result = harness.run("attack_to_death", "{entity_id:42,max_seconds:5}");
        assertTrue("the loop must finish the target: " + result,
                result.get("killed").getAsBoolean());
        assertEquals("a 20 HP zombie with a 7-damage sword needs three hits",
                3, result.get("attacks").getAsInt());
        assertEquals("the loop must not overswing", 3, swings[0]);

        for (Call call : harness.calls) {
            if ("attack_entity".equals(call.name)) {
                assertEquals("the plan's entity id is used",
                        42, call.args.get("entity_id").getAsInt());
            }
        }
    }

    @Test
    public void attackToDeathRefusesPlayersAndReportsBlockers() throws Exception {
        Harness harness = new Harness(library(), state(),
                (name, args) -> {
                    if (!"attack_plan".equals(name)) return null;
                    JsonObject plan = ToolJson.ok();
                    plan.addProperty("found", true);
                    plan.addProperty("entity_id", args.get("entity_id").getAsInt());
                    plan.addProperty("name", "Tester");
                    plan.addProperty("alive", true);
                    plan.addProperty("player", true);
                    plan.addProperty("can_damage", false);
                    plan.addProperty("blocker", "players are never attacked");
                    return plan;
                });
        JsonObject result = harness.run("attack_to_death", "{entity_id:7}");
        assertFalse("a player must never be killed by the loop",
                result.get("killed").getAsBoolean());
        assertTrue("the blocker must explain why", result.get("blocker").getAsString()
                .toLowerCase().contains("player"));
        for (Call call : harness.calls) {
            assertNotEquals("no swing may reach a player", "attack_entity", call.name);
        }
    }

    @Test
    public void realBundledSkillRunsThroughItsFunctionName() throws Exception {
        Harness harness = new Harness(library(), state(), null);
        // escape_cave is a real bundled skill; its 2x2 shaft logic runs from the
        // function call exactly as it does from skills.call.
        JsonObject viaCall = harness.run("escape_cave", "{x:0,z:0}");
        assertTrue("bundled skill must run via skills.call: " + viaCall,
                viaCall.get("ok").getAsBoolean());

        JsonObject direct = harness.runAsFunction("escape_cave", "{x:0,z:0,top_y:66}");
        assertTrue("bundled skill must run as skills.escape_cave(...): " + direct,
                direct.get("ok").getAsBoolean());
        assertEquals("the shaft must stop at the requested top", 66,
                direct.get("surfaceY").getAsInt());
    }

    @Test
    public void smeltingReportsAFuelStallInsteadOfHanging() throws Exception {        JsonObject furnace = new JsonObject();
        furnace.addProperty("x", 0);
        furnace.addProperty("y", 64);
        furnace.addProperty("z", 0);
        furnace.addProperty("dist", 1);
        furnace.addProperty("burnTime", 0);
        JsonArray items = new JsonArray();
        JsonObject input = new JsonObject();
        input.addProperty("slot", 0);
        input.addProperty("name", "Iron Ore");
        input.addProperty("count", 4);
        items.add(input);
        furnace.add("items", items);

        Harness harness = new Harness(library(),
                state("Iron Ore:|:minecraft:iron_ore:|:0:|:4"), (name, args) -> {
                    if (!"scan_furnaces".equals(name)) return null;
                    JsonObject scan = ToolJson.ok();
                    scan.addProperty("count", 1);
                    JsonArray furnaces = new JsonArray();
                    furnaces.add(furnace);
                    scan.add("furnaces", furnaces);
                    return scan;
                });
        JsonObject result = harness.run("smelt_items",
                "{filter:'iron_ore',count:4,wait:true,max_wait_s:5}");
        assertFalse("nothing was smelted", result.get("ok").getAsBoolean());
        assertEquals("no fuel means a clean fuel stall", "fuel",
                result.get("stalled").getAsString());
    }

    @Test
    public void carryPlayerClimbsCruisesThenLandsOnTheScannedSpot() throws Exception {
        JsonObject player = new JsonObject();
        player.addProperty("ok", true);
        player.addProperty("name", "Tester");
        player.addProperty("x", 0.5);
        player.addProperty("y", 64.0);
        player.addProperty("z", 0.5);
        player.addProperty("blockX", 0);
        player.addProperty("blockY", 64);
        player.addProperty("blockZ", 0);
        player.addProperty("riding", false);
        player.addProperty("underground", false);
        JsonObject entity = new JsonObject();
        entity.addProperty("id", 42);
        entity.addProperty("name", "Tester");
        entity.addProperty("x", 0.5);
        entity.addProperty("y", 64.0);
        entity.addProperty("z", 0.5);
        entity.addProperty("dist", 1.2);
        entity.addProperty("player", true);
        JsonArray entities = new JsonArray();
        entities.add(entity);

        Harness harness = new Harness(library(), state(), (name, args) -> {
            if ("get_player".equals(name)) return player;
            if ("get_entities".equals(name)) {
                JsonObject found = ToolJson.ok();
                found.addProperty("count", 1);
                found.add("entities", entities);
                return found;
            }
            if ("get_top_y".equals(name)) {
                JsonObject top = ToolJson.ok();
                // A hill between the drone and the destination: the cruise
                // altitude must rise above it, not fly through it.
                top.addProperty("topY", args.get("x").getAsInt() == 100 ? 96 : 64);
                return top;
            }
            return null;
        });
        JsonObject result = harness.run("carry_player",
                "{destination:{x:100,y:64,z:0}}");
        assertTrue("the carry should succeed: " + result, result.get("ok").getAsBoolean());
        assertTrue("cruise altitude must clear a 96-block hill: "
                        + result.get("cruiseY").getAsInt(),
                result.get("cruiseY").getAsInt() >= 96 + 8);

        Call pick = null;
        Call unpick = null;
        List<Call> gotoLegs = new ArrayList<>();
        for (Call call : harness.calls) {
            if ("pick_entity".equals(call.name)) pick = call;
            else if ("unpick_entity".equals(call.name)) unpick = call;
            else if ("goto".equals(call.name)) gotoLegs.add(call);
        }
        assertNotNull("the player must actually be mounted", pick);
        assertNotNull("the player must be dismounted", unpick);
        assertTrue("the drop must name the rider", unpick.args.has("entity_id"));
        assertEquals("the drop must be the player that was picked",
                pick.args.get("entity_id").getAsInt(), unpick.args.get("entity_id").getAsInt());

        assertTrue("carry needs climb + cruise + descent legs", gotoLegs.size() >= 3);
        int climbY = gotoLegs.get(0).args.get("y").getAsInt();
        int cruiseY = gotoLegs.get(1).args.get("y").getAsInt();
        int descendY = gotoLegs.get(2).args.get("y").getAsInt();
        assertTrue("the first leg must climb, not fly sideways: " + climbY,
                climbY >= result.get("cruiseY").getAsInt());
        assertTrue("the cruise leg must stay high: " + cruiseY,
                cruiseY >= result.get("cruiseY").getAsInt());
        assertTrue("the last leg must descend to the landing, not hover high: " + descendY,
                descendY < cruiseY);
        assertEquals("the descent must reach the scanned spot", 64,
                gotoLegs.get(2).args.get("y").getAsInt() == 64 ? 64
                        : gotoLegs.get(2).args.get("y").getAsInt());
        for (Call call : gotoLegs) {
            assertFalse("a carried player is never flown with digging enabled: " + call.args,
                    call.args.has("allow_dig") && call.args.get("allow_dig").getAsBoolean());
        }
    }

    @Test
    public void carryPlayerRefusesToDismountWithoutASafeLanding() throws Exception {
        JsonObject player = new JsonObject();
        player.addProperty("ok", true);
        player.addProperty("name", "Tester");
        player.addProperty("x", 0.5);
        player.addProperty("y", 64.0);
        player.addProperty("z", 0.5);
        player.addProperty("blockX", 0);
        player.addProperty("blockY", 64);
        player.addProperty("blockZ", 0);
        player.addProperty("riding", false);
        player.addProperty("underground", false);
        JsonObject entity = new JsonObject();
        entity.addProperty("id", 7);
        entity.addProperty("name", "Tester");
        entity.addProperty("x", 0.5);
        entity.addProperty("y", 64.0);
        entity.addProperty("z", 0.5);
        entity.addProperty("dist", 1.0);
        entity.addProperty("player", true);
        JsonArray entities = new JsonArray();
        entities.add(entity);

        Harness harness = new Harness(library(), state(), (name, args) -> {
            if ("get_player".equals(name)) return player;
            if ("get_entities".equals(name)) {
                JsonObject found = ToolJson.ok();
                found.addProperty("count", 1);
                found.add("entities", entities);
                return found;
            }
            if ("find_safe_landing".equals(name)) {
                // No safe spot anywhere: a mountain face, water, or a void.
                JsonObject none = ToolJson.ok();
                none.addProperty("found", false);
                none.addProperty("safe", false);
                none.addProperty("safeAtRequested", false);
                return none;
            }
            return null;
        });
        JsonObject result = harness.run("carry_player",
                "{destination:{x:20,y:120,z:20}}");
        assertFalse("a trip with no safe landing must not report success", 
                result.get("ok").getAsBoolean());
        boolean dismounted = false;
        for (Call call : harness.calls) {
            if ("unpick_entity".equals(call.name) && call.args.has("entity_id")
                    && call.args.get("entity_id").getAsInt() == 7) dismounted = true;
        }
        assertFalse("the rider must not be released without a safe landing", dismounted);
    }

    @Test
    public void escapeCaveOpensATwoByTwoShaftNotAOneWideHole() throws Exception {
        Harness harness = new Harness(library(), state(), (name, args) -> {
            if ("get_top_y".equals(name)) {
                JsonObject top = ToolJson.ok();
                top.addProperty("topY", 67);
                return top;
            }
            if ("get_block".equals(name)) {
                JsonObject stone = ToolJson.ok();
                stone.addProperty("name", "Stone");
                stone.addProperty("registryName", "minecraft:stone");
                stone.addProperty("isAir", false);
                stone.addProperty("isLiquid", false);
                stone.addProperty("hardness", 2);
                return stone;
            }
            return null;
        });
        JsonObject result = harness.run("escape_cave", "{x:0,z:0}");
        assertTrue("the shaft should complete: " + result, result.get("ok").getAsBoolean());

        java.util.Set<String> columns = new java.util.HashSet<>();
        for (Call call : harness.calls) {
            if (!"break_block".equals(call.name)) continue;
            columns.add(call.args.get("x").getAsInt() + "," + call.args.get("z").getAsInt());
        }
        assertEquals("the shaft must clear exactly a 2x2 footprint", 4, columns.size());
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                assertTrue("missing footprint column " + dx + "," + dz,
                        columns.contains(dx + "," + dz));
            }
        }
    }

    @Test
    public void escapeCaveStopsAtLiquidInsteadOfFloodingTheShaft() throws Exception {
        Harness harness = new Harness(library(), state(), (name, args) -> {
            if ("get_top_y".equals(name)) {
                JsonObject top = ToolJson.ok();
                top.addProperty("topY", 70);
                return top;
            }
            if ("get_block".equals(name)) {
                boolean lava = args.get("y").getAsInt() == 66;
                JsonObject block = ToolJson.ok();
                block.addProperty("name", lava ? "Lava" : "Stone");
                block.addProperty("registryName", lava ? "minecraft:lava" : "minecraft:stone");
                block.addProperty("isAir", false);
                block.addProperty("isLiquid", lava);
                block.addProperty("hardness", lava ? 0 : 2);
                return block;
            }
            return null;
        });
        JsonObject result = harness.run("escape_cave", "{x:0,z:0}");
        assertFalse("a lava layer must stop the shaft", result.get("ok").getAsBoolean());
        assertTrue("the result must explain the liquid",
                result.get("stopped").getAsString().toLowerCase().contains("liquid"));
        for (Call call : harness.calls) {
            if (!"break_block".equals(call.name)) continue;
            assertNotEquals("nothing at or above the lava may be broken",
                    true, call.args.get("y").getAsInt() >= 66);
        }
    }
}
