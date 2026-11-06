package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class JsEngineTest {
    @Test
    public void scriptCallsAnAsyncToolAndReturnsAValue() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(ToolSpec.builder("add")
                .description("Add two numbers.")
                .parameters(ToolSchema.object()
                        .requiredInt("a", "First.")
                        .requiredInt("b", "Second."))
                .affinity(ToolAffinity.ASYNC)
                .build());
        registry.bind("add", "test", (context, args) -> {
            JsonObject result = ToolJson.ok();
            result.addProperty("sum", args.get("a").getAsInt() + args.get("b").getAsInt());
            return result;
        });

        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        JsonObject result = engine.execute(null,
                "const r = await drone.add({ a: 2, b: 3 }); log('sum', r.sum); return r.sum;", 5_000L);

        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(5, result.get("result").getAsInt());
        assertEquals(1, result.getAsJsonArray("logs").size());
    }

    @Test
    public void toolFailureIsThrownAndCatchableInScript() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(ToolSpec.builder("fail")
                .description("Always fails.")
                .parameters(ToolSchema.object())
                .affinity(ToolAffinity.ASYNC)
                .build());
        registry.bind("fail", "test", (context, args) ->
                ToolJson.error("expected", "boom", "Fix it.", true));

        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        JsonObject result = engine.execute(null,
                "try { drone.fail(); return 'no'; } catch (e) { return 'caught:' + e.message; }", 5_000L);

        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertTrue(result.get("result").getAsString().startsWith("caught:"));
        assertTrue(result.get("result").getAsString().contains("boom"));
    }

    @Test
    public void uncaughtToolFailureReturnsTheStandardEnvelope() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(ToolSpec.builder("fail")
                .description("Always fails.")
                .parameters(ToolSchema.object())
                .affinity(ToolAffinity.ASYNC)
                .build());
        registry.bind("fail", "test", (context, args) ->
                ToolJson.error("expected", "boom", "Fix it.", true));

        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        JsonObject result = engine.execute(null, "drone.fail();", 5_000L);

        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("expected", result.get("code").getAsString());
        assertTrue(result.get("error").getAsString().contains("boom"));
    }

    @Test
    public void javaReflectionBridgeExposesRealClasses() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        JsonObject result = engine.execute(null,
                "const A = Java.type('java.util.ArrayList');"
                        + "const list = new A(); list.add('x');"
                        + "return { size: list.size(), item: list.get(0) };", 5_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(1, result.getAsJsonObject("result").get("size").getAsInt());
        assertEquals("x", result.getAsJsonObject("result").get("item").getAsString());
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-js").toFile();
    }
}
