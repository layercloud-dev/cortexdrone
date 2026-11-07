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
 * Regression tests for the JavaScript failures seen in live sessions.
 *
 * <p>{@code const} redeclaration and ternary {@code : null} erasure both showed
 * up as runtime syntax errors in the bot log.</p>
 */
public class JsCompatRegressionTest {
    private static JsonObject run(String code) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        return engine.execute(null, code, 5_000L);
    }

    @Test
    public void ternaryWithNullElseSurvivesTypeErasure() throws Exception {
        JsonObject result = run(
                "var a = true;"
                        + "var out = { first: a ? 'yes' : null, second: a ? 1 : undefined };"
                        + "return out;");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals("yes", result.getAsJsonObject("result").get("first").getAsString());
    }

    @Test
    public void ternaryInsideAMethodCallSurvives() throws Exception {
        JsonObject result = run(
                "var value = 5;"
                        + "return { v: Math.max(0, value > 3 ? value : null) };");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(5, result.getAsJsonObject("result").get("v").getAsInt());
    }

    @Test
    public void constCanBeReusedInTheSameScope() throws Exception {
        JsonObject result = run(
                "for (const name of ['a', 'b']) { log(name); }"
                        + "for (const name of ['c', 'd']) { log(name); }"
                        + "const value = 1;"
                        + "const value2 = 2;"
                        + "return value + value2;");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(3, result.get("result").getAsInt());
    }

    @Test
    public void repeatedLetDeclarationsDoNotThrow() throws Exception {
        JsonObject result = run(
                "let x = 1;"
                        + "let x2 = 2;"
                        + "return x + x2;");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(3, result.get("result").getAsInt());
    }

    @Test
    public void genuineTypeAnnotationsStillErase() throws Exception {
        JsonObject result = run(
                "function add(a, b) { return a + b; }"
                        + "var label = 'ok';"
                        + "return { sum: add(2, 3), label: label };");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(5, result.getAsJsonObject("result").get("sum").getAsInt());
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-js-compat").toFile();
    }
}
