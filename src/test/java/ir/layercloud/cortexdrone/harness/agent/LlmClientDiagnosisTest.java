package ir.layercloud.cortexdrone.harness.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Gateway failures must produce advice the player can act on.
 *
 * <p>Live evidence (2026-11-13 journal): six sessions ended with
 * {@code "AI backend error: ... HTTP 429 ... Rate limit exceeded:
 * free-models-per-day"} and the reply told the player to "fix the gateway/key".
 * That is the wrong setting, and the client also retried it seven times.
 */
public class LlmClientDiagnosisTest {
    @Test
    public void aDailyFreeQuotaIsNotRetried() {
        LlmClient.GatewayException quota = new LlmClient.GatewayException(429,
                "LLM gateway HTTP 429 - Rate limit exceeded: free-models-per-day. Add 10 credits");
        assertFalse("a per-day quota cannot succeed on retry", LlmClient.isTransient(quota));

        LlmClient.GatewayException burst = new LlmClient.GatewayException(429,
                "LLM gateway HTTP 429 - too many requests per second");
        assertTrue("a burst limit can succeed on retry", LlmClient.isTransient(burst));
    }

    @Test
    public void serverErrorsStayRetryable() {
        assertTrue(LlmClient.isTransient(new LlmClient.GatewayException(503, "HTTP 503")));
        assertTrue(LlmClient.isTransient(new LlmClient.GatewayException(408, "timeout")));
        assertFalse(LlmClient.isTransient(new LlmClient.GatewayException(401, "bad key")));
    }

    @Test
    public void theAdviceNamesTheSettingThatActuallyFailed() {
        String quota = LlmFailureAdvice.diagnose(
                "GatewayException: LLM gateway HTTP 429 - free-models-per-day. Add 10 credits");
        assertTrue(quota, quota.contains("daily free quota"));
        assertTrue("the fix is credits or a different model", quota.contains("/drone config model"));

        String missing = LlmFailureAdvice.diagnose("GatewayException: LLM gateway HTTP 404");
        assertTrue(missing, missing.contains("404"));
        assertTrue("a 404 is a URL/model problem, not a key problem",
                missing.contains("apiUrl") || missing.contains("model id"));

        String key = LlmFailureAdvice.diagnose("GatewayException: LLM gateway HTTP 401");
        assertTrue(key, key.contains("/drone config key"));

        assertTrue(LlmFailureAdvice.diagnose("some unknown failure")
                .contains("gateway/key"));
    }
}
