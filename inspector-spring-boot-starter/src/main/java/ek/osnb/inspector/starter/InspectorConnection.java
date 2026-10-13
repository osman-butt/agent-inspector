package ek.osnb.inspector.starter;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the inspector at startup. Timeouts are short and failures return {@code false}:
 * an inspector that isn't running must never slow down or break the application.
 */
class InspectorConnection {

    private static final String SIGNATURE = "\"name\":\"agent-inspector\"";

    private record RouteRequest(String route, String upstream) {
    }

    private final String url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    InspectorConnection(String url) {
        this.url = url;
    }

    /**
     * True only when the server identifies itself as the inspector, so traffic is never
     * routed to some other service that happens to listen on the same port.
     */
    boolean isUp() {
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/ping")).timeout(Duration.ofMillis(500)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains(SIGNATURE);
        } catch (Exception e) {
            return false;
        }
    }

    boolean registerRoute(String route, String upstream) {
        try {
            String body = json.writeValueAsString(new RouteRequest(route, upstream));
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/routes"))
                            .timeout(Duration.ofSeconds(1))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
