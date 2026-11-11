package ir.layercloud.cortexdrone.harness.js;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The TypeScript eraser must not mistake an object value for a type annotation.
 *
 * <p>Regression: {@code { max_wait_ms: LIMITS.maxWaitMs, ... }} was rewritten to
 * {@code { max_wait_ms, ... }} because the dotted constant looked like a
 * namespace type, so Rhino then reported {@code "max_wait_ms" is not defined}.</p>
 */
public class TypeScriptEraserObjectValueTest {
    @Test
    public void aDottedConstantObjectValueSurvivesErasure() {
        String source = "var args = { max_wait_ms: LIMITS.maxWaitMs, n: 1 };";
        String erased = TypeScriptEraser.erase(source);
        assertTrue("the object value must survive: " + erased,
                erased.contains("max_wait_ms: LIMITS.maxWaitMs"));
    }

    @Test
    public void aRealParameterAnnotationIsStillErased() {
        String source = "function f(max_wait_ms: number) { return max_wait_ms; }";
        String erased = TypeScriptEraser.erase(source);
        assertFalse("the annotation should be erased: " + erased,
                erased.contains(": number"));
        assertTrue("the parameter itself must remain: " + erased,
                erased.contains("max_wait_ms"));
    }
}
