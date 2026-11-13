package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolCatalog;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.combat.TntDropLimits;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The TNT strike must be a real, discoverable capability: a declared tool, a
 * bundled skill, and schema bounds that match the Java safety policy.
 */
public class TntStrikeSkillTest {
    private static JsonObject tntDropSchema() {
        for (ToolSpec spec : ToolCatalog.standard()) {
            if ("tnt_drop".equals(spec.getName())) return spec.getParameters();
        }
        throw new AssertionError("tnt_drop is not declared");
    }

    private static JsonObject property(String name) {
        return tntDropSchema().getAsJsonObject("properties").getAsJsonObject(name);
    }

    @Test
    public void theToolIsDeclaredWithTheJavaBounds() {
        assertEquals("integer", property("count").get("type").getAsString());
        assertEquals(TntDropLimits.MIN_COUNT, property("count").get("minimum").getAsInt());
        assertEquals(TntDropLimits.MAX_COUNT, property("count").get("maximum").getAsInt());
        assertEquals(TntDropLimits.DEFAULT_COUNT, property("count").get("default").getAsInt());

        assertEquals(TntDropLimits.MIN_FUSE_TICKS, property("fuse_ticks").get("minimum").getAsInt());
        assertEquals(TntDropLimits.MAX_FUSE_TICKS, property("fuse_ticks").get("maximum").getAsInt());
        assertEquals(TntDropLimits.DEFAULT_FUSE_TICKS, property("fuse_ticks").get("default").getAsInt());

        assertEquals(0.0D, property("spread").get("minimum").getAsDouble(), 0.001D);
        assertEquals(TntDropLimits.MAX_SPREAD, property("spread").get("maximum").getAsDouble(), 0.001D);
    }

    @Test
    public void theSkillIsBundledAndParameterized() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-tnt-skill").toFile();
        SkillLibrary library = new SkillLibrary(root);
        Skill skill = library.get("tnt_strike");
        assertNotNull("tnt_strike must ship as a bundled skill", skill);
        assertEquals("bundled", skill.getSource());
        assertTrue("the skill must call the tnt_drop tool", skill.getCode().contains("drone.tntDrop("));
        assertTrue("the skill must escape before the fuse ends",
                skill.getCode().contains("escape_to") || skill.getCode().contains("escape"));
        assertTrue("the skill must not spawn an entity by hand",
                !skill.getCode().contains("EntityTNTPrimed"));
        assertTrue("the skill must be discoverable by its triggers",
                !library.search("bomb that base with tnt", 3).isEmpty());
        assertEquals("tnt_strike", library.search("drop tnt on it", 3).get(0).getName());
    }

    @Test
    public void theSkillIsListedInTheBundledIndex() throws Exception {
        String index = new String(Files.readAllBytes(
                new File("src/main/resources/skills/index.json").toPath()), "UTF-8");
        assertTrue(index.contains("\"tnt_strike\""));
    }

    @Test
    public void theSafetyEnvelopeIsNotWeakenedByTheSkill() throws Exception {
        String code = new SkillLibrary(Files.createTempDirectory("cortexdrone-tnt-guard").toFile())
                .get("tnt_strike").getCode();
        assertFalse("fuse must never be zero (instant detonation on release)",
                code.contains("fuse_ticks: 0"));
        assertTrue("the skill must refuse a mounted passenger",
                code.contains("passenger is riding"));
        assertTrue("the skill must take TNT when the drone has none",
                code.contains("takeFromPlayer"));
    }
}
