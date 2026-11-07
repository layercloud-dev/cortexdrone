package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolCatalog;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.OcTools;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Code Mode must reach the OpenComputers tools like any other tool. */
public class OcCodeModeTest {
    /**
     * Echo bindings with the same names and argument names as the OC tools.
     * ASYNC affinity keeps the test off the server-thread bridge, which needs a
     * live manager; the real specs use MAIN and are covered by OcToolsTest.
     */
    private static JsEngine engine() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(ToolSpec.builder("oc_state")
                .description("Echo OC state.")
                .parameters(ToolSchema.object()
                        .optionalBoolean("include_methods", "Include methods.", true))
                .affinity(ToolAffinity.ASYNC)
                .build());
        registry.declare(ToolSpec.builder("oc_methods")
                .description("List OC methods.")
                .parameters(ToolSchema.object()
                        .optionalString("query", "Filter.")
                        .optionalInt("limit", "Limit.", 1, 256, 64)
                        .optionalBoolean("docs", "Docs.", false))
                .affinity(ToolAffinity.ASYNC)
                .build());
        registry.declare(ToolSpec.builder("oc_call")
                .description("Call an OC callback.")
                .parameters(ToolSchema.object()
                        .requiredString("method", "Callback name.")
                        .optionalObject("args", "Positional arguments.")
                        .optionalString("arguments", "JSON array."))
                .affinity(ToolAffinity.ASYNC)
                .build());
        registry.declare(ToolSpec.builder("oc_machine")
                .description("Drive the OC machine.")
                .parameters(ToolSchema.object()
                        .requiredEnum("control", "Machine control.", "start", "stop", "pause", "signal")
                        .optionalString("value", "Value.")
                        .optionalObject("payload", "Payload."))
                .affinity(ToolAffinity.ASYNC)
                .build());

        registry.bind("oc_state", "test", (context, args) -> {
            JsonObject result = ToolJson.ok();
            result.addProperty("machine_state", "Running");
            result.addProperty("components", 3);
            return result;
        });
        registry.bind("oc_methods", "test", (context, args) -> {
            JsonObject result = ToolJson.ok();
            result.addProperty("count", 2);
            return result;
        });
        registry.bind("oc_call", "test", (context, args) -> {
            JsonObject result = ToolJson.ok();
            result.addProperty("method", args.get("method").getAsString());
            result.addProperty("value", true);
            return result;
        });
        registry.bind("oc_machine", "test", (context, args) -> {
            JsonObject result = ToolJson.ok();
            result.addProperty("control", args.get("control").getAsString());
            return result;
        });
        return new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
    }

    @Test
    public void realOcSpecsAreInTheModelCatalog() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ToolSpec spec : ToolCatalog.standard()) names.add(spec.getName());
        assertTrue(names.contains("oc_state"));
        assertTrue(names.contains("oc_methods"));
        assertTrue(names.contains("oc_call"));
        assertTrue(names.contains("oc_machine"));
        // And they are real provider specs, not stray duplicates.
        assertEquals(4, OcTools.specs().size());
    }

    @Test
    public void toolsAppearInTheCodeModeCatalog() throws Exception {
        JsonObject result = engine().execute(null,
                "var names = [];"
                        + "var list = tools.list();"
                        + "for (var i = 0; i < list.length; i++) names.push(list[i].name);"
                        + "return { oc: names.filter(function(n) { return n.indexOf('oc_') === 0; }) };",
                10_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        String found = result.getAsJsonObject("result").getAsJsonArray("oc").toString();
        assertTrue(found, found.contains("oc_state"));
        assertTrue(found, found.contains("oc_call"));
        assertTrue(found, found.contains("oc_machine"));
    }

    @Test
    public void camelCaseBindingsWork() throws Exception {
        JsonObject result = engine().execute(null,
                "var s = drone.ocState({});"
                        + "var m = drone.ocMethods({ query: 'move' });"
                        + "var c = drone.ocCall({ method: 'detect' });"
                        + "var k = drone.ocMachine({ control: 'start' });"
                        + "return { state: s.machine_state, methods: m.count,"
                        + " call: c.value, machine: k.control };",
                10_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        JsonObject value = result.getAsJsonObject("result");
        assertEquals("Running", value.get("state").getAsString());
        assertEquals(2, value.get("methods").getAsInt());
        assertTrue(value.get("call").getAsBoolean());
        assertEquals("start", value.get("machine").getAsString());
    }

    @Test
    public void snakeCaseBindingsWorkToo() throws Exception {
        JsonObject result = engine().execute(null,
                "return drone.oc_state({}).machine_state;", 10_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals("Running", result.get("result").getAsString());
    }

    @Test
    public void mixedSnakeAndCamelToolNamesStillResolve() throws Exception {
        // Live models wrote `getTop_y`; both get_top_y and getTopY are bound, and
        // the namespace folds separators and case on a miss.
        JsonObject result = engine().execute(null,
                "return { a: typeof drone.oc_state, b: typeof drone.ocState,"
                        + " c: typeof drone.oc_State, d: typeof drone.OCSTATE };", 10_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        JsonObject value = result.getAsJsonObject("result");
        assertEquals("function", value.get("a").getAsString());
        assertEquals("function", value.get("b").getAsString());
        assertEquals("function", value.get("c").getAsString());
        assertEquals("function", value.get("d").getAsString());
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-oc-js").toFile();
    }
}
