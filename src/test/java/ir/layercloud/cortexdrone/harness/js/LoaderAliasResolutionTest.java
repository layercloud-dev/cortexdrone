package ir.layercloud.cortexdrone.harness.js;

import cpw.mods.fml.common.Loader;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The {@code Loader.activeModList()} failure from the live journal.
 *
 * <p>Two different errors appeared: {@code NoSuchMethodException:
 * Loader.activeModList()} and {@code Cannot call property activeModList ...it
 * is "object"}. On this 1.7.10 source it is a getter property, not a method, so
 * the alias has to resolve to {@code getActiveModList}.</p>
 */
public class LoaderAliasResolutionTest {
    @Test
    public void bothSpellingsResolveThroughTheAliasTable() {
        assertEquals("getActiveModList", McpAliases.method(Loader.class, "activeModList"));
        assertEquals("activeModList", McpAliases.method(Loader.class, "getActiveModList"));
        assertNull("an unmapped name must not guess", McpAliases.method(Loader.class, "notARealMember"));
    }

    @Test
    public void aScriptResolvesASrgSpellingToTheDevMember() throws Exception {
        // Behavioural proof of bidirectional resolution through the wrapper:
        // this workspace is MCP-named, so only `getKeys` exists. A script that
        // writes the production spelling `func_77594_a` (as the live sessions
        // do) must still reach it.
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(
                        Files.createTempDirectory("cortexdrone-alias").toFile()));
        com.google.gson.JsonObject result = engine.execute(null, ""
                + "var R = Java.type('net.minecraft.util.RegistrySimple');\n"
                + "var reg = new R();\n"
                + "var viaMcp = reg.getKeys();\n"
                + "var viaSrg = reg.func_148742_b();\n"
                + "return { same: String(viaMcp) === String(viaSrg), srgWorks: viaSrg !== null };", 8_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        com.google.gson.JsonObject value = result.getAsJsonObject("result");
        assertTrue("both spellings must reach the same method: " + value,
                value.get("srgWorks").getAsBoolean());
        assertTrue("the resolved method must be identical: " + value,
                value.get("same").getAsBoolean());
    }

    @Test
    public void theWrapperConsultsTheAliasTable() throws Exception {
        String wrapper = new String(Files.readAllBytes(new File(
                "src/main/java/ir/layercloud/cortexdrone/harness/js/McpJavaWrappers.java").toPath()), "UTF-8");
        assertTrue("ObjectWrapper.get must consult the alias table",
                wrapper.contains("McpAliases.method(owner, name)"));
        assertTrue("ClassWrapper.get must consult the alias table",
                wrapper.contains("McpAliases.method(getClassObject(), name)"));
    }
}
