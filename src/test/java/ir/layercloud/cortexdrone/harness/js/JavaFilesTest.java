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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code file:} URLs from a code source must become usable paths.
 *
 * <p>Live failure: {@code new java.io.File(url.getPath())} threw
 * {@code InvalidPathException: Illegal char <:> at index 4} for
 * {@code file:\F:\PrismLauncher\...\HBM.jar}.
 */
public class JavaFilesTest {
    @Test
    public void aFileUrlWithADriveLetterLosesTheScheme() {
        assertEquals("F:/PrismLauncher/instances/a.jar",
                JavaFiles.toPath("file:/F:/PrismLauncher/instances/a.jar"));
        assertEquals("F:/dir/a.jar", JavaFiles.toPath("file:F:/dir/a.jar"));
        assertEquals("C:/x y/a.jar", JavaFiles.toPath("file:///C:/x%20y/a.jar"));
    }

    @Test
    public void aJarEntryUrlStopsAtTheArchive() {
        assertEquals("F:/mods/HBM.jar",
                JavaFiles.toPath("jar:file:/F:/mods/HBM.jar!/com/hbm/MainRegistry.class"));
        assertEquals("F:/mods/HBM.jar",
                JavaFiles.toPath("file:/F:/mods/HBM.jar!/com/hbm/MainRegistry.class"));
    }

    @Test
    public void aPlainPathIsReturnedUnchanged() {
        assertEquals("/home/user/a.jar", JavaFiles.toPath("/home/user/a.jar"));
        assertEquals("relative/a.jar", JavaFiles.toPath("relative/a.jar"));
    }

    @Test
    public void aRealFileUrlProducesARealFile() throws Exception {
        File temp = Files.createTempDirectory("cortexdrone-fileurl").toFile();
        File jar = new File(temp, "HBM.jar");
        assertTrue(jar.createNewFile());
        File resolved = JavaFiles.fileFrom(jar.toURI().toURL());
        assertNotNull(resolved);
        assertTrue("the resolved file must exist: " + resolved, resolved.isFile());
    }

    @Test
    public void theScriptGlobalsConvertAUrlInOneStep() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(
                        Files.createTempDirectory("cortexdrone-fileurl-script").toFile()));
        JsonObject result = engine.execute(null, ""
                + "var u = new java.net.URL('file:/F:/mods/HBM.jar');\n"
                + "var f = toFile(u.getPath());\n"
                + "return { path: toPath(u), file: String(f.getPath()) };", 5_000L);        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        JsonObject value = result.getAsJsonObject("result");
        assertEquals("F:/mods/HBM.jar", value.get("path").getAsString());
        assertEquals("toFile must resolve the same bytes",
                new File("F:/mods/HBM.jar").getPath(), value.get("file").getAsString());
    }
}
