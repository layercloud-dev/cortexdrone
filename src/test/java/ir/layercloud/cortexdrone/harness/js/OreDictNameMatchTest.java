package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * The skill runtime's {@code nameMatches} must understand OreDictionary tags,
 * because skills see display names like "Cassiterite" and players ask for
 * "tin".
 */
public class OreDictNameMatchTest {
    private static JsonObject run(String code) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        return engine.execute(null, code, 5_000L);
    }

    private static boolean matches(String name, String registry, String filter, String tagsJs) throws Exception {
        JsonObject result = run("return nameMatches(" + js(name) + ", " + js(registry) + ", "
                + js(filter) + ", " + tagsJs + ");");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        return result.get("result").getAsBoolean();
    }

    private static String js(String value) {
        return value == null ? "null" : "'" + value.replace("'", "\\'") + "'";
    }

    private static final String TIN_TAGS = "['oreTin','ingotTin']";

    @Test
    public void bareMaterialWordMatchesTaggedItem() throws Exception {
        // The display name says nothing about tin, but the dictionary does.
        assertTrue(matches("Cassiterite Ore", "gregtech:gt.blockores", "tin", TIN_TAGS));
        assertTrue(matches("Tin Ingot", "thermalfoundation:material", "tin", TIN_TAGS));
    }

    @Test
    public void explicitTagMatchesOnlyThatTag() throws Exception {
        String oreTags = "['oreTin']";
        String ingotTags = "['ingotTin']";
        assertTrue(matches("Cassiterite Ore", "gregtech:gt.blockores", "#oreTin", oreTags));
        assertFalse(matches("Tin Ingot", "thermalfoundation:material", "#oreTin", ingotTags));
        assertTrue(matches("Tin Ingot", "thermalfoundation:material", "oredict:ingotTin", ingotTags));
    }

    @Test
    public void tagMatchingDoesNotBreakPlainNames() throws Exception {
        assertTrue(matches("Iron Ore", "minecraft:iron_ore", "iron", "null"));
        assertFalse(matches("Iron Ore", "minecraft:iron_ore", "tin", "null"));
        assertTrue(matches("Stone", "minecraft:stone", "all", "null"));
    }

    @Test
    public void exclusionStillWorksWithTags() throws Exception {
        assertFalse(matches("Tin Ingot", "thermalfoundation:material", "!tin", TIN_TAGS));
        assertTrue(matches("Iron Ore", "minecraft:iron_ore", "iron|!tin", TIN_TAGS));
    }

    @Test
    public void countInInventoryUsesTags() throws Exception {
        String code = "var inv = ["
                + "{name:'Cassiterite Ore', registryName:'gregtech:ore', count:5, oreDict:['oreTin']},"
                + "{name:'Iron Ore', registryName:'minecraft:iron_ore', count:3, oreDict:['oreIron']}"
                + "];"
                + "return countInInventory(inv, 'tin');";
        JsonObject result = run(code);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(5, result.get("result").getAsInt());
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-oredict").toFile();
    }
}
