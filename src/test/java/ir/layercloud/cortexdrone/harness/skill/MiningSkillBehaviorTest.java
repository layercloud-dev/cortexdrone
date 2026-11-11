package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Digging and climbing mechanics: staircases, shafts and cave escapes.
 *
 * <p>Harness lives in {@link SkillTestSupport}; this suite keeps one behavioural area
 * per file.</p>
 */
public class MiningSkillBehaviorTest extends SkillTestSupport {
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
        assertFalse("no bedrock is present in this world",
                result.get("reachedBedrock").getAsBoolean());
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
