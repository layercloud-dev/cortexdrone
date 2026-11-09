package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.skill.SkillLimits;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Skill time budgets have one owner. */
public class SkillToolsLimitTest {
    private static ToolSpec skillSpec() {
        for (ToolSpec spec : SkillTools.specs()) {
            if ("skill_run".equals(spec.getName())) return spec;
        }
        fail("skill_run is not declared");
        return null;
    }

    private static int bound(String key) {
        return skillSpec().getParameters().getAsJsonObject("properties")
                .getAsJsonObject("timeout_ms").get(key).getAsInt();
    }

    @Test
    public void theSchemaAdvertisesTheOwnerBounds() {
        assertEquals(SkillLimits.MIN_TIMEOUT_MS, bound("minimum"));
        assertEquals(SkillLimits.MAX_TIMEOUT_MS, bound("maximum"));
        assertEquals(SkillLimits.DEFAULT_TIMEOUT_MS, bound("default"));
    }

    @Test
    public void theSourceReadsTheOwner() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/ir/layercloud/cortexdrone/harness/tools/SkillTools.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("SkillLimits.DEFAULT_TIMEOUT_MS"));
        assertTrue(source.contains("SkillLimits.EXECUTE_TIMEOUT_MS"));
    }
}
