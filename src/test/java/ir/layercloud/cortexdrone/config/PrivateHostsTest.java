package ir.layercloud.cortexdrone.config;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One private-host predicate shared by the LLM client (proxy bypass) and the web
 * service (SSRF guard).
 */
public class PrivateHostsTest {
    @Test
    public void loopbackIsPrivate() {
        assertTrue(PrivateHosts.isPrivateHost("localhost"));
        assertTrue(PrivateHosts.isPrivateHost("127.0.0.1"));
        assertTrue(PrivateHosts.isPrivateHost("127.10.20.30"));
        assertTrue(PrivateHosts.isPrivateHost("::1"));
        assertTrue(PrivateHosts.isPrivateHost("[::1]"));
    }

    @Test
    public void rfc1918RangesArePrivate() {
        assertTrue(PrivateHosts.isPrivateHost("10.0.0.1"));
        assertTrue(PrivateHosts.isPrivateHost("192.168.1.10"));
        assertTrue(PrivateHosts.isPrivateHost("172.16.0.1"));
        assertTrue(PrivateHosts.isPrivateHost("172.31.255.254"));
    }

    @Test
    public void publicAddressesAreNotPrivate() {
        assertFalse(PrivateHosts.isPrivateHost("8.8.8.8"));
        assertFalse(PrivateHosts.isPrivateHost("api.example.com"));
        assertFalse(PrivateHosts.isPrivateHost("172.32.0.1"));
        assertFalse(PrivateHosts.isPrivateHost("172.15.0.1"));
        assertFalse(PrivateHosts.isPrivateHost("11.0.0.1"));
    }

    @Test
    public void urlsAreResolvedToTheirHost() {
        assertTrue(PrivateHosts.isPrivateUrl("http://127.0.0.1:7002/v1"));
        assertTrue(PrivateHosts.isPrivateUrl("http://localhost:8080/"));
        assertFalse(PrivateHosts.isPrivateUrl("https://api.example.com/v1"));
        assertFalse(PrivateHosts.isPrivateUrl(null));
        assertFalse(PrivateHosts.isPrivateUrl("not a url"));
    }

    @Test
    public void blankInputsAreSafe() {
        assertFalse(PrivateHosts.isPrivateHost(null));
        assertFalse(PrivateHosts.isPrivateHost(""));
        assertFalse(PrivateHosts.isPrivateUrl(""));
    }
}
