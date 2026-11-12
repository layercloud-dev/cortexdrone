package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Farming mechanics: till every column, then sow every column.
 *
 * <p>Harness lives in {@link SkillTestSupport}; this suite keeps one behavioural area
 * per file.</p>
 */
public class FarmingSkillBehaviorTest extends SkillTestSupport {
    @Test
    public void plantingTillsThenSowsEveryColumn() throws Exception {
        Set<String> tilledColumns = new HashSet<>();
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
}
