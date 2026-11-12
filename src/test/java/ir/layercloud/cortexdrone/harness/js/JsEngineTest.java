package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.*;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

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

    @Test
    public void skillsCallComposesStoredSkillsWithParameters() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(ToolSpec.builder("echo")
                .description("Echo a value.")
                .parameters(ToolSchema.object().requiredString("value", "Value."))
                .affinity(ToolAffinity.ASYNC)
                .build());
        registry.bind("echo", "test", (context, args) -> {
            JsonObject result = ToolJson.ok();
            result.addProperty("value", args.get("value").getAsString());
            return result;
        });

        SkillLibrary library = new SkillLibrary(tempRoot());
        assertNotNull(library.save("echo_upper", "upper", "", null, null,
                "var r = drone.echo({ value: params.value }); return r.value + '!';", "test"));
        assertNotNull(library.save("echo_twice", "twice", "", null,
                Collections.singletonList("echo_upper"),
                "var once = skills.call('echo_upper', { value: params.value });"
                        + "return once + once;", "test"));

        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), library);
        JsonObject result = engine.execute(null,
                "return skills.call('echo_twice', { value: 'ab' });", 5_000L);

        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals("ab!ab!", result.get("result").getAsString());
    }

    @Test
    public void unknownSkillThrowsAClearRuntimeError() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));
        JsonObject result = engine.execute(null, "return skills.call('nope', {});", 5_000L);
        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("nope"));
    }

    /**
     * Engine whose skill library has one echo skill that prefixes its input.
     */
    private static JsEngine skillEngine(SkillLibrary library) {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(ToolSpec.builder("echo")
                .description("Echo a value.")
                .parameters(ToolSchema.object().requiredString("value", "Value."))
                .affinity(ToolAffinity.ASYNC)
                .build());
        registry.bind("echo", "test", (context, args) -> {
            JsonObject result = ToolJson.ok();
            result.addProperty("value", args.get("value").getAsString());
            return result;
        });
        return new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, new ModConfig(), library);
    }

    @Test
    public void aSkillIsCallableAsAJavaScriptFunction() throws Exception {
        SkillLibrary library = new SkillLibrary(tempRoot());
        assertNotNull(library.save("echo_upper", "upper", "", null, null,
                "var r = drone.echo({ value: params.value }); return r.value + '!';", "test"));
        JsEngine engine = skillEngine(library);

        // snake_case, camelCase and a case/separator typo must all resolve.
        for (String call : new String[]{
                "skills.echo_upper({ value: 'ab' })",
                "skills.echoUpper({ value: 'ab' })",
                "skills.EchoUpper({ value: 'ab' })",
                "skills['echo_upper']({ value: 'ab' })"}) {
            JsonObject result = engine.execute(null, "return " + call + ";", 5_000L);
            assertTrue(call + " -> " + result, result.get("ok").getAsBoolean());
            assertEquals(call, "ab!", result.get("result").getAsString());
        }

        JsonObject result = engine.execute(null,
                "return skills.echoUpper({ value: 'ab' });", 5_000L);
        assertEquals("ab!", result.get("result").getAsString());
    }

    @Test
    public void boundSkillFunctionsComposeAndReceiveParams() throws Exception {
        SkillLibrary library = new SkillLibrary(tempRoot());
        assertNotNull(library.save("echo_upper", "upper", "", null, null,
                "var r = drone.echo({ value: params.value }); return r.value + '!';", "test"));
        assertNotNull(library.save("echo_pair", "pair", "", null,
                Collections.singletonList("echo_upper"),
                "var once = skills.echoUpper({ value: params.value });"
                        + "var twice = skills.call('echo_upper', { value: once });"
                        + "return once + '|' + twice;", "test"));
        JsEngine engine = skillEngine(library);

        JsonObject result = engine.execute(null,
                "return skills.echoPair({ value: 'ab' });", 5_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals("ab!|ab!!", result.get("result").getAsString());
    }

    @Test
    public void skillFunctionCanBePassedAroundAsAValue() throws Exception {
        SkillLibrary library = new SkillLibrary(tempRoot());
        assertNotNull(library.save("echo_upper", "upper", "", null, null,
                "var r = drone.echo({ value: params.value }); return r.value + '!';", "test"));
        JsEngine engine = skillEngine(library);

        JsonObject result = engine.execute(null,
                "var fn = skills.fn('echo_upper');"
                        + "var out = [];"
                        + "for (var i = 0; i < 2; i++) out.push(fn({ value: 'x' + i }));"
                        + "return out;", 5_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertEquals("x0!", result.get("result").getAsJsonArray().get(0).getAsString());
        assertEquals("x1!", result.get("result").getAsJsonArray().get(1).getAsString());
    }

    @Test
    public void skillsNamesListsEveryStoredSkill() throws Exception {
        SkillLibrary library = new SkillLibrary(tempRoot());
        assertNotNull(library.save("echo_upper", "upper", "", null, null,
                "var r = drone.echo({ value: params.value }); return r.value + '!';", "test"));
        JsEngine engine = skillEngine(library);
        JsonObject result = engine.execute(null, "return skills.names();", 5_000L);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        String names = result.get("result").toString();
        assertTrue("stored skill must be listed: " + names, names.contains("echo_upper"));
        assertTrue("bundled skills must be listed too: " + names, names.contains("carry_player"));
    }

    @Test
    public void aSkillNamedLikeAHelperDoesNotReplaceIt() throws Exception {
        SkillLibrary library = new SkillLibrary(tempRoot());
        // A learned skill may not shadow the generic helpers; it stays reachable
        // through skills.call / skills.fn.
        Skill saved = library.save("search", "shadow", "", null, null,
                "return 'hijacked';", "test");
        assertNotNull("this test needs a stored skill named 'search'", saved);
        JsEngine engine = skillEngine(library);

        // The generic helper is still the metadata call (here it fails because
        // this test engine has no `skill` tool, which proves it was not replaced
        // by the stored skill).
        JsonObject helperResult = engine.execute(null,
                "try { return skills.search('echo').ok; } catch (e) { return 'helper:' + e.message; }",
                5_000L);
        assertTrue(helperResult.toString(), helperResult.get("ok").getAsBoolean());
        assertTrue("search must remain the metadata helper: " + helperResult,
                helperResult.get("result").getAsString().startsWith("helper:"));

        // The stored skill is still callable by name.
        JsonObject shadowResult = engine.execute(null,
                "return skills.call('search', {});", 5_000L);
        assertTrue(shadowResult.toString(), shadowResult.get("ok").getAsBoolean());
        assertEquals("hijacked", shadowResult.get("result").getAsString());
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-js").toFile();
    }
}
