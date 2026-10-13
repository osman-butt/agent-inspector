package ek.osnb.inspector.server.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BodyCopyTest {

    @Test
    void keepsBodiesUnderTheLimit() {
        assertThat(BodyCopy.of(bytes("hello"), 10).toText(null)).isEqualTo("hello");
    }

    @Test
    void truncatesBodiesOverTheLimit() {
        assertThat(BodyCopy.of(bytes("hello world"), 5).toText(null))
                .startsWith("hello")
                .contains("truncated after 5 bytes");
    }

    @Test
    void emptyBodyIsNull() {
        assertThat(BodyCopy.of(new byte[0], 10).toText(null)).isNull();
    }

    @Test
    void decodesGzip() throws IOException {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            out.write(bytes("{\"ok\":true}"));
        }
        assertThat(BodyCopy.of(gzipped.toByteArray(), 1000).toText("gzip")).isEqualTo("{\"ok\":true}");
    }

    @Test
    void redactsSecretHeadersOnly() {
        assertThat(ProxyController.redact("Authorization", "Bearer sk-proj-abcdefghijklmnop"))
                .isEqualTo("Bearer sk-…(redacted)");
        assertThat(ProxyController.redact("Content-Type", "application/json")).isEqualTo("application/json");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
