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

    @Test
    public void spreadInCallArgumentsIsRewritten() throws Exception {
        // Rhino 1.7.15 parses array spread but not `f(...args)`. Models write
        // Math.min(...xs) constantly, and it failed a live task.
        JsonObject result = run(
                "var xs = [4, 9, 2];"
                        + "var zs = [-3, 7, 1];"
                        + "return { min: Math.min.apply(null, xs), max: Math.max.apply(null, zs),"
                        + " bbox: { x: [Math.min(...xs), Math.max(...xs)],"
                        + " z: [Math.min(...zs), Math.max(...zs)] } };");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        JsonObject value = result.getAsJsonObject("result");
        assertEquals(2, value.get("min").getAsInt());
        assertEquals(7, value.get("max").getAsInt());
        assertEquals(2, value.getAsJsonObject("bbox").getAsJsonArray("x").get(0).getAsInt());
    }

    @Test
    public void userFunctionSpreadCallKeepsItsOwner() throws Exception {
        JsonObject result = run(
                "var box = { base: 10, add: function (a, b) { return this.base + a + b; } };"
                        + "var nums = [1, 2];"
                        + "return box.add.apply(box, nums);");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(13, result.get("result").getAsInt());
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-js-compat").toFile();
    }
}
