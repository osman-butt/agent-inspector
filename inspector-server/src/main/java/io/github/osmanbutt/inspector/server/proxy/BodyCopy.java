package io.github.osmanbutt.inspector.server.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * A copy of a body passing through the proxy, capped so a huge upload or download
 * can't exhaust the inspector's memory.
 */
class BodyCopy {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final long limit;
    private boolean truncated;

    BodyCopy(long limit) {
        this.limit = limit;
    }

    static BodyCopy of(byte[] body, long limit) {
        BodyCopy copy = new BodyCopy(limit);
        copy.write(body, 0, body.length);
        return copy;
    }

    void write(byte[] buffer, int offset, int length) {
        long room = limit - bytes.size();
        if (length > room) {
            truncated = true;
            length = (int) Math.max(room, 0);
        }
        bytes.write(buffer, offset, length);
    }

    String toText(String contentEncoding) {
        if (bytes.size() == 0) {
            return null;
        }
        String text = decode(contentEncoding);
        return truncated ? text + "\n…[truncated after " + limit + " bytes]" : text;
    }

    private String decode(String contentEncoding) {
        if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip") && !truncated) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "<gzip body could not be decoded: " + e.getMessage() + ">";
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
