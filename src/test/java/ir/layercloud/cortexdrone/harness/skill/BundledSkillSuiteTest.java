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
        assertTrue(library.get("build_structure").getCode().contains("skills.call('place_build_plan'"));
        assertTrue(library.get("farm_cycle").getCode().contains("skills.call('harvest_crops'"));
        assertTrue(library.get("patrol").getCode().contains("skills.call('guard_scan'"));
    }

    @Test
    public void expectedCapabilitiesAreBundled() throws Exception {
        SkillLibrary library = library();
        for (String name : new String[]{
                "guard", "patrol", "carry_player", "sort_chests", "hunt",
                "harvest_crops", "sugarcane_farm", "melon_pumpkin_farm", "chop_tree",
                "build_structure", "place_build_plan", "survey_build_site",
                "light_area", "deliver_items", "withdraw_items", "collect_drops",
                "return_to_player", "mine_blocks", "mine_and_deliver"}) {
            assertNotNull("missing bundled skill " + name, library.get(name));
        }
    }

    @Test
    public void searchFindsTheRightCapability() throws Exception {
        SkillLibrary library = library();
        assertEquals("guard", library.search("guard the base at night", 3).get(0).getName());
        assertEquals("sort_chests", library.search("organise the chests", 3).get(0).getName());
        assertEquals("build_structure", library.search("build me a house", 3).get(0).getName());
        assertEquals("harvest_crops", library.search("replant the wheat farm", 3).get(0).getName());
    }
}
