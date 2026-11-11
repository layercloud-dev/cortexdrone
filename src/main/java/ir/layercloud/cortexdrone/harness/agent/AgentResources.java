package ir.layercloud.cortexdrone.harness.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads packaged prompt resources with a safe empty fallback.
 */
public final class AgentResources {
    private AgentResources() {
    }

    public static String load(String resource) {
        try (InputStream stream = AgentResources.class.getResourceAsStream(resource)) {
            if (stream == null) return "";
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
