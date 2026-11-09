package ir.layercloud.cortexdrone.harness.agent;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A gateway on a private LAN address or a bare hostname is local enough to
 * bypass a system proxy; a public host must not.
 *
 * <p>Regression: only {@code 127.0.0.1} and {@code localhost} were recognised,
 * so a gateway on {@code 10.x}/{@code 192.168.x} or an internal hostname was
 * sent through the JVM proxy and failed to connect.</p>
 */
public class LlmClientEndpointTest {
    @Test
    public void loopbackAndPrivateGatewaysBypassTheProxy() {
        assertTrue(LlmClient.isLocalGateway("http://127.0.0.1:7002/v1/chat/completions"));
        assertTrue(LlmClient.isLocalGateway("http://localhost:7002/v1"));
        assertTrue(LlmClient.isLocalGateway("http://[::1]:7002/v1"));
        assertTrue(LlmClient.isLocalGateway("http://10.1.2.3:7002/v1"));
        assertTrue(LlmClient.isLocalGateway("http://192.168.1.20:7002/v1"));
        assertTrue(LlmClient.isLocalGateway("http://172.16.0.5:7002/v1"));
    }

    @Test
    public void publicGatewaysUseTheDefaultProxyPath() {
        assertFalse(LlmClient.isLocalGateway("https://api.example.com/v1/chat/completions"));
        assertFalse(LlmClient.isLocalGateway("https://gateway.layercloud.ir/v1"));
    }

    @Test
    public void malformedUrlsAreTreatedAsRemote() {
        assertFalse(LlmClient.isLocalGateway(null));
        assertFalse(LlmClient.isLocalGateway(""));
        assertFalse(LlmClient.isLocalGateway("not a url"));
    }
}
