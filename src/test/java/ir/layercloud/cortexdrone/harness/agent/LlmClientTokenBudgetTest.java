package ir.layercloud.cortexdrone.harness.agent;

import org.junit.Test;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The agent loop widens {@code max_tokens} when a thinking model returns an
 * empty completion. If the model's own ceiling is lower, that recovery turns
 * into an HTTP 400; the client must recognise it and fall back instead of
 * ending the task with a backend error.
 */
public class LlmClientTokenBudgetTest {
    @Test
    public void aMaxTokensRejectionIsRecognised() {
        assertTrue(LlmClient.isTokenBudgetRejection(new LlmClient.GatewayException(400,
                "LLM gateway HTTP 400 - {\"error\":{\"message\":\"max_tokens is too large\"}}")));
        assertTrue(LlmClient.isTokenBudgetRejection(new LlmClient.GatewayException(400,
                "max_completion_tokens exceeds the model limit")));
        assertTrue(LlmClient.isTokenBudgetRejection(new LlmClient.GatewayException(400,
                "maximum output tokens is 8192")));
    }

    @Test
    public void otherErrorsAreNotMistakenForABudgetRejection() {
        assertFalse(LlmClient.isTokenBudgetRejection(new LlmClient.GatewayException(400,
                "invalid model")));
        assertFalse(LlmClient.isTokenBudgetRejection(new LlmClient.GatewayException(401,
                "max_tokens is too large")));
        assertFalse(LlmClient.isTokenBudgetRejection(new LlmClient.GatewayException(500,
                "max_tokens is too large")));
        assertFalse(LlmClient.isTokenBudgetRejection(null));
    }

    /**
     * Regression: the gateway sometimes accepts a request and then closes the
     * socket ("Unexpected end of file from server"). That is not an HTTP status,
     * so it bypassed the retry loop and a single upstream hiccup ended the task.
     */
    @Test
    public void aDroppedConnectionIsRetryable() {
        assertTrue(LlmClient.isNetworkFailure(new IllegalStateException(
                "Could not read the gateway response",
                new SocketException("Unexpected end of file from server"))));
        assertTrue(LlmClient.isNetworkFailure(new EOFException("closed")));
        assertTrue(LlmClient.isNetworkFailure(new SocketTimeoutException("read timed out")));
        assertFalse(LlmClient.isNetworkFailure(new IllegalArgumentException("bad key")));
    }
}
