package ir.layercloud.cortexdrone.harness.agent;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * A truncated HTTP body must never be mistaken for a complete response.
 *
 * <p>Regression: {@code LlmClient.read} caught every exception and returned
 * whatever had been read so far, so a connection dropped mid-body produced a
 * short "valid" string that then failed JSON parsing with a misleading
 * "invalid JSON" message instead of a network error.</p>
 */
public class LlmClientBodyTest {
    /**
     * A stream that yields one line then fails, like a dropped connection.
     */
    private static InputStream failsMidBody() {
        byte[] first = "{\"choices\":[".getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            private int index;

            @Override
            public int read() throws IOException {
                if (index < first.length) return first[index++] & 0xFF;
                throw new IOException("connection reset");
            }
        };
    }

    @Test
    public void aCompleteBodyIsRead() throws Exception {
        String body = "{\"ok\":true}";
        assertEquals(body, LlmClient.readBody(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void aDroppedConnectionIsReportedNotTruncated() {
        try {
            String partial = LlmClient.readBody(failsMidBody());
            fail("a truncated body must not be returned as success, got: " + partial);
        } catch (IOException expected) {
            assertTrue("the reason must name the failure",
                    expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    @Test
    public void anEmptyStreamIsAnEmptyBody() throws Exception {
        assertEquals("", LlmClient.readBody(new ByteArrayInputStream(new byte[0])));
        assertEquals("", LlmClient.readBody(null));
    }
}
