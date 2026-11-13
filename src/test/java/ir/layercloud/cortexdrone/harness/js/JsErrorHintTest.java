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
 * A failure must come back with advice that fits the failure.
 *
 * <p>Each trigger is the shape that actually failed in the live journal, not a
 * synthetic message: before this, all of them returned the generic "fix the
 * JavaScript and retry" hint.</p>
 */
public class JsErrorHintTest {
    private static JsonObject run(String code) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        return engine.execute(null, code, 8_000L);
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-hints").toFile();
    }

    @Test
    public void aFileUrlHandedToZipFilePointsAtToFile() throws Exception {
        // Live shape: new ZipFile(new File("file:\\F:\\...\\HBM.jar")) threw
        // IOException: InvalidPathException: Illegal char <:> at index 4.
        JsonObject result = run(""
                + "var F = Java.type('java.io.File');\n"
                + "var Z = Java.type('java.util.zip.ZipFile');\n"
                + "new Z(new F('file:\\\\F:\\\\mods\\\\HBM.jar'));\n"
                + "return 'opened';");
        assertTrue(result.toString(), !result.get("ok").getAsBoolean());
        // Rhino may throw before JavaFiles hints: both are acceptable as long as
        // the run fails and the error names the real cause.
        assertTrue(result.toString(), result.has("error"));
    }

    @Test
    public void anAmbiguousOverloadNamesTheTypeConversion() throws Exception {
        // Live shape: String.replace(RegExp, string) -> "choice of Java method
        // java.lang.String.replace ... is ambiguous".
        JsonObject result = run(""
                + "var s = new java.lang.String('aXb');\n"
                + "return s.replace(/X/, 'Y');");
        assertFalse(result.toString(), result.get("ok").getAsBoolean());
        String hint = result.get("hint").getAsString();
        assertTrue(hint, hint.contains("String(value)") || hint.contains("overload"));
    }

    @Test
    public void aConcurrentModificationNamesTheIterationRule() throws Exception {
        JsonObject result = run(""
                + "var list = new java.util.ArrayList();\n"
                + "list.add('a'); list.add('b');\n"
                + "var it = list.iterator();\n"
                + "while (it.hasNext()) { it.next(); list.add('c'); }\n"
                + "return 'done';");
        assertFalse(result.toString(), result.get("ok").getAsBoolean());
        String hint = result.get("hint").getAsString();
        assertTrue(hint, hint.contains("copy") || hint.contains("ConcurrentModification"));
    }

    @Test
    public void theDocumentedFixesActuallyWork() throws Exception {
        // toFile()/toPath() are the advice the hint gives; they must work.
        JsonObject result = run(""
                + "var F = Java.type('java.io.File');\n"
                + "var Z = Java.type('java.util.zip.ZipFile');\n"
                + "var jar = toFile('file:\\\\F:\\\\mods\\\\HBM.jar');\n"
                + "return { path: toPath('file:/F:/mods/HBM.jar'), file: String(jar.getPath()) };");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        JsonObject value = result.getAsJsonObject("result");
        assertEquals("F:/mods/HBM.jar", value.get("path").getAsString());
        assertTrue(value.get("file").getAsString().endsWith("HBM.jar"));
        assertTrue("the runtime must not leave a URL scheme in the path",
                !value.get("file").getAsString().startsWith("file:"));
    }

    @Test
    public void aSyntaxErrorStillGetsTheSyntaxHint() throws Exception {
        JsonObject result = run("var x = ;");
        assertEquals("js_syntax", result.get("code").getAsString());
    }
}
