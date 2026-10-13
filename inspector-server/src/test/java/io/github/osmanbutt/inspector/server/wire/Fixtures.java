package io.github.osmanbutt.inspector.server.wire;

import io.github.osmanbutt.inspector.server.capture.Exchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Exchanges built from recorded wire traffic under {@code src/test/resources/wire}. */
public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/wire/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Exchange exchange(String path, String requestBody, int status, String responseBody) {
        return pending(path, requestBody)
                .complete(new Exchange.Response(status, Map.of(), responseBody, 100, null));
    }

    public static Exchange pending(String path, String requestBody) {
        return pending("test", path, requestBody, Map.of());
    }

    public static Exchange pending(String route, String path, String requestBody, Map<String, String> headers) {
        return Exchange.start(route, new Exchange.Request("POST", "http://upstream" + path, path, headers, requestBody));
    }
}
