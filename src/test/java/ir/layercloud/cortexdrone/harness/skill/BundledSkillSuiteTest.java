package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.harness.js.JsSource;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Invariants for the bundled skill suite.
 *
 * <p>Bundled skills are data, so a typo would otherwise only surface at runtime
 * in the middle of a task. These tests compile every body with Rhino and check
 * that the composition graph is sound.</p>
 */
public class BundledSkillSuiteTest {
    private static SkillLibrary library() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-bundled").toFile();
        return new SkillLibrary(root);
    }

    @Test
    public void bundledSkillsLoadFromTheirOwnDirectories() throws Exception {
        SkillLibrary library = library();
        for (String name : new String[]{"guard", "build_structure", "harvest_crops", "sort_chests"}) {
            Skill skill = library.get(name);
            assertNotNull("missing directory-backed skill " + name, skill);
            assertEquals("bundled", skill.getSource());
            assertNull("bundled codeFile is inlined and cleared", skill.getCodeFile());
            assertTrue(skill.getCode().trim().length() > 40);
            assertNotNull("SKILL.md body is kept as docs", skill.getDocs());
            assertTrue("SKILL.md body for " + name + " is too short",
                    skill.getDocs().trim().length() > 80);
            assertTrue("SKILL.md body for " + name + " needs usage headings",
                    skill.getDocs().contains("## "));
        }
        assertTrue(library.get("guard").getCode().contains("skills.call('guard_scan'"));

        com.google.gson.JsonObject loaded = library.toJsonArray(
                java.util.Collections.singletonList(library.get("guard")), true)
                .get(0).getAsJsonObject();
        assertTrue("skill action=load must return the SKILL.md docs", loaded.has("docs"));
        assertTrue(loaded.get("docs").getAsString().contains("## Parameters"));
    }

    @Test
    public void skillMarkdownRoundTripPreservesMetadataAndDocs() {
        Skill original = new Skill();
        original.setName("round_trip");
        original.setDescription("A \"quoted\" description: with colon");
        original.setTags("one two");
        original.setTriggers(java.util.Arrays.asList("do the thing", "again"));
        original.setRequires(java.util.Collections.singletonList("mine_blocks"));
        original.setVersion(4);
        original.setUses(7);
        original.setSource("refined");
        original.setDocs("# Round trip\n\nBody with **markdown**.\n");
        original.setCode("return { ok: true };");

        String text = SkillMarkdown.write(original);
        Skill parsed = SkillMarkdown.read(text, null);
        assertNotNull(parsed);
        assertEquals(original.getName(), parsed.getName());
        assertEquals(original.getDescription(), parsed.getDescription());
        assertEquals(original.getTags(), parsed.getTags());
        assertEquals(original.getTriggers(), parsed.getTriggers());
        assertEquals(original.getRequires(), parsed.getRequires());
        assertEquals(original.getVersion(), parsed.getVersion());
        assertEquals(original.getUses(), parsed.getUses());
        assertEquals(original.getSource(), parsed.getSource());
        assertEquals(original.getDocs().trim(), parsed.getDocs());
    }

    @Test
    public void everyBundledSkillCompilesAsAFunctionBody() throws Exception {
        SkillLibrary library = library();
        List<Skill> all = library.all();
        assertFalse(all.isEmpty());
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            for (Skill skill : all) {
                String wrapper = "(function(__params) {\n"
                        + "var params = (__params === null || __params === undefined) ? {} : __params;\n"
                        + JsSource.prepare(skill.getCode())
                        + "\n})";
                try {
                    cx.compileString(wrapper, "skill:" + skill.getName(), 1, null);
                } catch (Throwable failure) {
                    fail("Bundled skill '" + skill.getName() + "' does not compile: " + failure);
                }
            }
        } finally {
            Context.exit();
        }
    }

    @Test
    public void requiredSkillsAllExistAndAreNotSelfReferential() throws Exception {
        SkillLibrary library = library();
        Set<String> names = new HashSet<>();
        for (Skill skill : library.all()) names.add(skill.getName());
        List<String> problems = new ArrayList<>();
        for (Skill skill : library.all()) {
            for (String dependency : skill.getRequires()) {
                String key = SkillLibrary.normalize(dependency);
                if (key.equals(skill.getName())) {
                    problems.add(skill.getName() + " requires itself");
                } else if (!names.contains(key)) {
                    problems.add(skill.getName() + " requires missing skill '" + key + "'");
                }
            }
            if (skill.getCode() == null || skill.getCode().trim().length() < 40) {
                problems.add(skill.getName() + " has no usable code");
            }
        }
        assertTrue(problems.toString(), problems.isEmpty());
    }

    @Test
    public void compoundWorkflowsCallTheirDependencies() throws Exception {
        SkillLibrary library = library();
        assertTrue(library.get("mine_and_deliver").getCode().contains("skills.call('mine_blocks'"));
        assertTrue(library.get("mine_and_deliver").getCode().contains("skills.call('deliver_items'"));
        assertTrue(library.get("guard").getCode().contains("skills.call('guard_scan'"));
        assertTrue(library.get("guard_scan").getCode().contains("skills.call('attack_to_death'"));
        assertTrue(library.get("hunt").getCode().contains("skills.call('attack_to_death'"));

        com.google.gson.JsonObject loaded = library.toJsonArray(
                java.util.Collections.singletonList(library.get("guard")), true)
                .get(0).getAsJsonObject();
        assertTrue("skill action=load must return the SKILL.md docs", loaded.has("docs"));
        assertTrue(loaded.get("docs").getAsString().contains("## Parameters"));
        assertTrue(library.get("build_structure").getCode().contains("skills.call('place_build_plan'"));
        assertTrue(library.get("farm_cycle").getCode().contains("skills.call('harvest_crops'"));
        assertTrue(library.get("patrol").getCode().contains("skills.call('guard_scan'"));
        assertTrue(library.get("gather_distant").getCode().contains("skills.call('mine_blocks'"));
        assertTrue(library.get("gather_distant").getCode().contains("skills.call('deliver_items'"));
        assertTrue(library.get("ore_to_ingots").getCode().contains("skills.call('smelt_items'"));
        assertTrue(library.get("ore_to_ingots").getCode().contains("skills.call('deliver_items'"));
        assertTrue(library.get("fetch_materials").getCode().contains("skills.call('withdraw_items'"));
        assertTrue(library.get("craft_items").getCode().contains("skills.call('withdraw_items'"));
        assertTrue(library.get("plant_farm").getCode().contains("skills.call('craft_items'"));
        assertTrue(library.get("fertilize_crops").getCode().contains("skills.call('craft_items'"));
    }

    @Test
    public void productionSkillsCarryTheirVanillaMechanics() throws Exception {
        SkillLibrary library = library();
        // Smelting must wait for the world instead of advancing cook time.
        String smelt = library.get("smelt_items").getCode();
        assertTrue("smelt waits for the world", smelt.contains("sleep("));
        assertFalse("smelt must not write cook time", smelt.contains("furnaceCookTime"));
        assertTrue("smelt knows the vanilla 200-tick cook", smelt.contains("200"));
        // The furnace slots are input 0, fuel 1, output 2.
        assertTrue(smelt.contains("slotInput = 0"));
        assertTrue(smelt.contains("slotFuel = 1"));
        assertTrue(smelt.contains("slotOutput = 2"));

        // Nether wart is mature at age 3 and grows on soul sand.
        String wart = library.get("nether_wart_farm").getCode();
        assertTrue(wart.contains("matureMeta = 3"));
        assertTrue(wart.contains("soul sand"));

        // Cactus keeps the base block and pops the column above it.
        String cactus = library.get("cactus_farm").getCode();
        assertTrue(cactus.contains("keep"));

        // Staircases keep the floor block: only the step and headroom are dug.
        String stair = library.get("dig_staircase").getCode();
        assertTrue(stair.contains("headroom"));
        assertTrue(stair.contains("standoffOf"));
    }

    @Test
    public void portalSkillUsesTheCanonicalFrameGeometry() throws Exception {
        SkillLibrary library = library();
        String code = library.get("build_nether_portal").getCode();
        assertTrue("4 wide", code.contains("frameWidth = 4"));
        assertTrue("5 tall", code.contains("frameHeight = 5"));
        assertTrue("obsidian frame", code.contains("'obsidian'"));
        assertTrue("flint and steel", code.contains("flint_and_steel"));
    }

    @Test
    public void expectedCapabilitiesAreBundled() throws Exception {
        SkillLibrary library = library();
        for (String name : new String[]{
                "guard", "guard_scan", "attack_to_death", "patrol", "carry_player",
                "sort_chests", "hunt",
                "harvest_crops", "sugarcane_farm", "bamboo_farm", "melon_pumpkin_farm", "chop_tree",
                "nether_wart_farm", "cactus_farm", "plant_farm", "fertilize_crops",
                "build_structure", "place_build_plan", "survey_build_site",
                "build_nether_portal",
                "replace_surface", "replace_box",
                "light_area", "deliver_items", "withdraw_items", "collect_drops",
                "fetch_materials", "smelt_items", "ore_to_ingots", "craft_items",
                "dig_staircase", "dig_shaft", "strip_mine", "quarry",
                "return_to_player", "mine_blocks", "mine_box", "mine_and_deliver",
                "gather_distant", "follow_player"}) {
            assertNotNull("missing bundled skill " + name, library.get(name));
        }
    }

    @Test
    public void searchFindsTheRightCapability() throws Exception {
        SkillLibrary library = library();
        assertEquals("guard", library.search("guard the base at night", 3).get(0).getName());
        assertEquals("attack_to_death", library.search("make sure the mob dies from its health", 3).get(0).getName());
        assertEquals("sort_chests", library.search("organise the chests", 3).get(0).getName());
        assertEquals("build_structure", library.search("build me a house", 3).get(0).getName());
        assertEquals("harvest_crops", library.search("replant the wheat farm", 3).get(0).getName());
        assertEquals("smelt_items", library.search("smelt items in the furnace and wait", 3).get(0).getName());
        assertEquals("dig_staircase", library.search("dig a staircase down", 3).get(0).getName());
        assertEquals("build_nether_portal", library.search("build a nether portal", 3).get(0).getName());
    }
}
