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
 * Prove the Java interop surface works end to end.
 */
@SuppressWarnings("unused")
public class JavaInteropTest {
    private static JsonObject run(ModConfig config, String code) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                registry, config, new SkillLibrary(tempRoot()));
        return engine.execute(null, code, 5_000L);
    }

    private static void ok(String code) throws Exception {
        JsonObject result = run(new ModConfig(), code);
        assertTrue(code + " -> " + result, result.get("ok").getAsBoolean());
    }

    private static void okValue(String code, String expected) throws Exception {
        JsonObject result = run(new ModConfig(), code);
        assertTrue(code + " -> " + result, result.get("ok").getAsBoolean());
        assertEquals(code, expected, result.get("result").getAsString());
    }

    @Test
    public void classLiteralAndPackagesWork() throws Exception {
        okValue("return Java.type('java.lang.String.class').getSimpleName();", "String");
        okValue("return Java.type('java.lang.String').class.getSimpleName();", "String");
        okValue("return java.lang.String.class.getSimpleName();", "String");
        okValue("return Packages.java.lang.String.class.getSimpleName();", "String");
        okValue("return new java.lang.String('abc').getClass().getName();", "java.lang.String");
    }

    @Test
    public void arraySuffixResolves() throws Exception {
        okValue("return Java.type('java.lang.String[]').getSimpleName();", "String[]");
        okValue("return Java.type('int[]').getComponentType().getSimpleName();", "int");
    }

    @Test
    public void javaNamespaceAndImportsWork() throws Exception {
        okValue("var l = new java.util.ArrayList(); l.add('x'); return l.size();", "1");
        okValue("importClass(java.util.ArrayList); var l = new ArrayList(); l.add('a'); return l.size();", "1");
        okValue("importPackage(java.util); var l = new ArrayList(); return l.isEmpty();", "true");
    }

    @Test
    public void reflectionCallsRealMethods() throws Exception {
        okValue("var C = java.lang.Class.forName('java.lang.String');"
                + "var m = C.getMethod('toUpperCase', null);"
                + "return m.invoke('abc', null);", "ABC");
        okValue("var A = java.lang.reflect.Array.newInstance(java.lang.String.class, 2);"
                + "java.lang.reflect.Array.set(A, 0, 'x');"
                + "return java.lang.reflect.Array.get(A, 0);", "x");
    }

    @Test
    public void javaArraysConvertBothWays() throws Exception {
        okValue("var A = Java.type('java.lang.String[]');"
                + "var a = Java.to(['a','b'], A); return a.length;", "2");
        okValue("var A = Java.type('java.lang.String[]');"
                + "var a = Java.to(['a','b'], A); return Java.from(a).join(',');", "a,b");
    }

    @Test
    public void importsAcceptStringsAndClasses() throws Exception {
        okValue("importClass('java.util.ArrayList'); var l = new ArrayList(); l.add('a'); return l.size();", "1");
        okValue("importPackage('java.util'); var l = new ArrayList(); return l.isEmpty();", "true");
        okValue("importClass(Java.type('java.util.ArrayList')); return new ArrayList().size();", "0");
    }

    @Test
    public void typePredicatesWork() throws Exception {
        okValue("return Java.isJavaObject(new java.util.ArrayList());", "true");
        okValue("return Java.isJavaObject({});", "false");
        okValue("return Java.isType(Java.type('java.lang.String'));", "true");
        okValue("return Java.getClassName(new java.util.ArrayList());", "java.util.ArrayList");
    }

    @Test
    public void minecraftClassesResolve() throws Exception {
        okValue("return Java.type('net.minecraft.world.World').getSimpleName();", "World");
        okValue("return Java.type('net.minecraft.world.World').class.getName();", "net.minecraft.world.World");
    }

    @Test
    public void reflectionCanBeDisabled() throws Exception {
        ModConfig config = new ModConfig();
        config.setAllowJavaReflection(false);

        assertFalse(run(config, "return Java.type('java.lang.String');")
                .get("ok").getAsBoolean());
        assertFalse(run(config, "return java.lang.String.class;")
                .get("ok").getAsBoolean());
        assertFalse(run(config, "return Packages.java.lang.String;")
                .get("ok").getAsBoolean());
        assertFalse(run(config, "importClass('java.util.ArrayList'); return new ArrayList();")
                .get("ok").getAsBoolean());
        // Ordinary tools and helpers keep working.
        JsonObject plain = run(config, "log('hi'); return 2 + 2;");
        assertTrue(plain.toString(), plain.get("ok").getAsBoolean());
        assertEquals(4, plain.get("result").getAsInt());
    }

    @Test
    public void mainBridgeRunsTheFunctionAndReturnsItsValue() throws Exception {
        JsonObject result = run(new ModConfig(),
                "var seen = null;"
                        + "var out = main(function() { seen = 'ran'; return 21 * 2; });"
                        + "return { out: out, seen: seen };");
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        JsonObject value = result.getAsJsonObject("result");
        assertEquals(42, value.get("out").getAsInt());
        assertEquals("ran", value.get("seen").getAsString());
    }

    @Test
    public void mainBridgeRejectsNonFunctions() throws Exception {
        JsonObject result = run(new ModConfig(), "return main('nope');");
        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("main(fn)"));
    }

    private static File tempRoot() throws Exception {
        return Files.createTempDirectory("cortexdrone-js-interop").toFile();
    }
}
