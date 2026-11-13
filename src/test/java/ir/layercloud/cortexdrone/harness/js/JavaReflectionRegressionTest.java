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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the reflection failures in the live session journal.
 *
 * <p>Each case is the exact script shape that failed in
 * {@code 20261113-014446-d2-l1-building-4-2215}: {@code Java.type("x.Y").class}
 * followed by {@code new C(...)} produced {@code JavaAdapter requires at least
 * one argument}, and a wrapped Java {@code String} compared with {@code ===}
 * never matched.</p>
 */
public class JavaReflectionRegressionTest {
    private static JsonObject run(String code) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        return engine.execute(null, code, 15_000L);
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-reflection").toFile();
    }

    @Test
    public void aClassLiteralCanBeConstructedWithNew() throws Exception {
        JsonObject result = run(""
                + "var C = Java.type('java.util.ArrayList').class;\n"
                + "var list = new C();\n"
                + "list.add('a');\n"
                + "return { size: list.size(), type: String(list.getClass().getName()) };");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        JsonObject value = result.getAsJsonObject("result");
        assertEquals(1, value.get("size").getAsInt());
        assertEquals("java.util.ArrayList", value.get("type").getAsString());
    }

    @Test
    public void aClassLiteralCanBeCalledWithoutNew() throws Exception {
        JsonObject result = run(""
                + "var C = Java.type('java.util.HashMap').class;\n"
                + "var map = C();\n"
                + "map.put('k', 'v');\n"
                + "return map.get('k');");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals("v", result.get("result").getAsString());
    }

    @Test
    public void aJavaStringFromAWrapperComparesEqualAsAString() throws Exception {
        // The lesson: compare String(obj.getName()) === 'x', because a wrapped
        // Java String is not a JS primitive and === never matched.
        JsonObject result = run(""
                + "var C = Java.type('java.util.ArrayList').class;\n"
                + "var name = new C().getClass().getName();\n"
                + "return { raw: name === 'java.util.ArrayList', stringed: String(name) === 'java.util.ArrayList' };");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        JsonObject value = result.getAsJsonObject("result");
        assertFalse("document the Rhino behaviour the lesson describes",
                value.get("raw").getAsBoolean());
        assertTrue(value.get("stringed").getAsBoolean());
    }

    @Test
    public void aClassLiteralStillAnswersReflectionMembers() throws Exception {
        JsonObject result = run(""
                + "var C = Java.type('java.util.ArrayList').class;\n"
                + "var methods = C.getDeclaredMethods();\n"
                + "return methods.length > 0;");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertTrue(result.get("result").getAsBoolean());
    }
}
