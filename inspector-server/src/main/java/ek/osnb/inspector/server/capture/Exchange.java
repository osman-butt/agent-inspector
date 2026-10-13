package ek.osnb.inspector.server.capture;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One HTTP round-trip between an application and a model API, as seen by the proxy.
 * The response is {@code null} while the call is still running.
 */
public record Exchange(
        String id,
        String route,
        Instant startedAt,
        Request request,
        Response response) {

    public record Request(
            String method,
            String url,
            String path,
            Map<String, String> headers,
            String body) {
    }

    /**
     * @param error set when the upstream could not be reached or the stream broke off
     */
    public record Response(
            int status,
            Map<String, String> headers,
            String body,
            long durationMs,
            String error) {
    }

    public static Exchange start(String route, Request request) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return new Exchange(id, route, Instant.now(), request, null);
    }

    public Exchange complete(Response response) {
        return new Exchange(id, route, startedAt, request, response);
    }
}
