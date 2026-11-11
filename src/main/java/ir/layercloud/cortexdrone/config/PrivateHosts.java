package ir.layercloud.cortexdrone.config;

import java.net.URL;
import java.util.Locale;

/**
 * One predicate for "this host is on a private network".
 *
 * <p>Two classes needed the answer with opposite polarity: {@code LlmClient}
 * bypasses a system proxy for a local gateway, {@code WebLookupService} refuses
 * to fetch private addresses (SSRF guard). Each had its own copy of the same
 * RFC1918 regex, so a fix had to be applied twice and could drift.</p>
 */
public final class PrivateHosts {
    private PrivateHosts() {
    }

    /**
     * True when the URL's host is loopback, link-local or RFC1918 private.
     */
    public static boolean isPrivateUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            return isPrivateHost(new URL(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when the host name/address is loopback, link-local or RFC1918.
     */
    public static boolean isPrivateHost(String host) {
        if (host == null) return false;
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        if (value.equals("localhost") || value.equals("::1") || value.equals("[::1]")) return true;
        if (value.startsWith("127.") || value.startsWith("10.")
                || value.startsWith("192.168.") || value.startsWith("169.254.")
                || value.startsWith("0.") || value.startsWith("::ffff:127.")) {
            return true;
        }
        // 172.16.0.0/12
        return value.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }
}
