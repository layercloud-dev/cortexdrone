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
 * Guards the Java-interop examples published in docs and the system prompt.
 */
public class JavaInteropDocExampleTest {
    private static JsonObject run(String code) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        return engine.execute(null, code, 5_000L);
    }

    @Test
    public void documentedMainBridgeExampleRuns() throws Exception {
        String code = "var mod = Java.type('ir.layercloud.cortexdrone.CortexDroneMod');\n"
                + "return main(function() {\n"
                + "  var player = mod.findPlayer(ctx.player);\n"
                + "  return player == null ? 'no-player' : 'player';\n"
                + "});";
        JsonObject result = run(code);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals("no-player", result.get("result").getAsString());
    }

    @Test
    public void modClassStaticsAreReachableThroughMain() throws Exception {
        JsonObject result = run("return main(function() {"
                + " return Java.type('ir.layercloud.cortexdrone.CortexDroneMod').VERSION; });");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertFalse(result.get("result").getAsString().isEmpty());
    }

    @Test
    public void minecraftClassReflectionWorksInsideMain() throws Exception {
        JsonObject result = run("var C = Java.type('net.minecraft.world.World').class;"
                + "return main(function() { return C.getDeclaredMethods().length > 0; });");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertTrue(result.get("result").getAsBoolean());
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-js-doc").toFile();
    }
}
