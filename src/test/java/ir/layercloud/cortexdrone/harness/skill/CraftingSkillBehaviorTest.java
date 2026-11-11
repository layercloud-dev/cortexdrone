package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Smelting fuel selection, fuel stalls, craft audits and portal dry runs.
 *
 * <p>Harness lives in {@link SkillTestSupport}; this suite keeps one behavioural area
 * per file.</p>
 */
public class CraftingSkillBehaviorTest extends SkillTestSupport {
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
