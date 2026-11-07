package ir.layercloud.cortexdrone.harness.js;

import org.junit.Test;

import static org.junit.Assert.*;

public class JsSourceTest {
    @Test
    public void awaitAndAsyncAreNormalizedBecauseToolsAreSynchronous() {
        String prepared = JsSource.prepare(
                "async function run() { const st = await drone.state(); return st; }\nrun();");
        assertFalse(prepared.contains("await"));
        assertFalse(prepared.contains("async function"));
        assertTrue(prepared.contains("drone.state()"));
    }

    @Test
    public void modernOperatorsAreRewrittenForRhino167() {
        String prepared = JsSource.prepare(
                "const a = res?.blocks?.[0] ?? 5; for (const b of a) { log(b); }");
        assertFalse(prepared.contains("?."));
        assertFalse(prepared.contains("??"));
        assertTrue(prepared.contains("for (var  b of a)") || prepared.contains("for (var b of a)"));
    }

    @Test
    public void stringLiteralsAreNotRewritten() {
        String prepared = JsSource.prepare("return { text: 'await ?? async for (const' };");
        assertTrue(prepared.contains("'await ?? async for (const'"));
    }

    @Test
    public void simpleTypeScriptAnnotationsAreErased() {
        String prepared = JsSource.prepare(
                "interface Foo { x: number }\n"
                        + "type Bar = string;\n"
                        + "const count: number = 2;\n"
                        + "function add(a: number, b: number): number { return a + b; }\n"
                        + "const value = count as number;\n"
                        + "return add(value, 3);");
        assertFalse(prepared.contains("interface Foo"));
        assertFalse(prepared.contains("type Bar"));
        assertFalse(prepared.contains(": number"));
        // const/let are lowered to var so a name can be reused in one scope.
        assertTrue(prepared.contains("var count = 2"));
        assertTrue(prepared.contains("function add("));
    }

    @Test
    public void objectLiteralsAreNotMistakenForTypeAnnotations() {
        String prepared = JsSource.prepare("const o = { a: 1, b: c, d: \"text\" }; return o;");
        assertTrue(prepared.contains("a: 1"));
        assertTrue(prepared.contains("b: c"));
        assertTrue(prepared.contains("d: \"text\""));
    }

    @Test
    public void spreadIsRewrittenForSimpleExpressions() {
        String prepared = JsSource.prepare(
                "const a = [...items]; const b = {...obj}; return [a, b];");
        assertTrue(prepared.contains("items.slice()"));
        assertTrue(prepared.contains("Object.assign({}, obj)"));
    }
}
