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
                    "survey_build_site", "plan_structure", "get_blocks_in_box"}) {
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
    public void smeltingReportsAFuelStallInsteadOfHanging() throws Exception {
        JsonObject furnace = new JsonObject();
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
}
