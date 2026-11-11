package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;

/**
 * {@code web_fetch} must decode a gzip response before returning it.
 *
 * <p>Regression: both branches of the {@code Content-Encoding: gzip} check
 * returned the raw bytes as UTF-8, so every compressing host produced binary
 * garbage instead of text.</p>
 */
public class WebLookupDecodingTest {
    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    public void gzipBodiesAreDecoded() throws Exception {
        String body = "{\"ok\":true,\"text\":\"hello world\"}";
        String decoded = WebLookupService.decodeBody(gzip(body), "gzip");
        assertEquals(body, decoded);
    }

    @Test
    public void plainBodiesArePassedThrough() {
        String body = "plain text";
        assertEquals(body, WebLookupService.decodeBody(body.getBytes(StandardCharsets.UTF_8), null));
        assertEquals(body, WebLookupService.decodeBody(body.getBytes(StandardCharsets.UTF_8), ""));
    }

    @Test
    public void gzipEncodingIsMatchedCaseInsensitivelyAndWithParameters() throws Exception {
        String body = "case insensitive";
        assertEquals(body, WebLookupService.decodeBody(gzip(body), "GZIP"));
        assertEquals(body, WebLookupService.decodeBody(gzip(body), "gzip, chunked"));
    }

    @Test
    public void corruptGzipFallsBackToRawTextInsteadOfThrowing() {
        byte[] corrupt = "not really gzip".getBytes(StandardCharsets.UTF_8);
        assertEquals("not really gzip", WebLookupService.decodeBody(corrupt, "gzip"));
    }
}
