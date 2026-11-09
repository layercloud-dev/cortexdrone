package ir.layercloud.cortexdrone.harness.agent;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * LoopTracer runs inside the error path of the agent loop; it must never throw.
 *
 * <p>Regression: {@code maskKey("a")} called {@code substring(length - 2)}
 * before the short-key branch, so a one-character API key crashed the handler
 * that was trying to log an LLM failure.</p>
 */
public class LoopTracerTest {
    @Test
    public void maskKeyNeverThrowsForShortKeys() {
        // Too short to mask meaningfully; the contract is "never crash".
        assertEquals("a", LoopTracer.maskKey("a"));
        assertEquals("ab", LoopTracer.maskKey("ab"));
        assertEquals("abcdef", LoopTracer.maskKey("abcdef"));
    }

    @Test
    public void mediumKeysKeepOnlyTheTail() {
        assertEquals("abc...fg", LoopTracer.maskKey("abcdefg"));
        assertEquals("abc...yz", LoopTracer.maskKey("abcdefghijyz"));
    }

    @Test
    public void maskKeyNeverEchoesTheWholeSecret() {
        String secret = "sk-1234567890abcdef";
        String masked = LoopTracer.maskKey(secret);
        assertFalse("must not echo the full key", masked.contains(secret));
        assertTrue("must keep a recognisable tail", masked.contains("..." + secret.substring(secret.length() - 2)));
        assertTrue("must report the length", masked.contains("len=" + secret.length()));
    }

    @Test
    public void maskKeyHandlesNullAndEmpty() {
        assertEquals("<empty>", LoopTracer.maskKey(null));
        assertEquals("<empty>", LoopTracer.maskKey(""));
    }
}
