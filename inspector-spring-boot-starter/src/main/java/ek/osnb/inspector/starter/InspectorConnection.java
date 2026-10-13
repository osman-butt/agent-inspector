package ek.osnb.inspector.starter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the inspector at startup. Timeouts are short and failures are answers, never
 * exceptions: an inspector that isn't running must not slow down or break the application.
 */
class InspectorConnection {

    enum State {
        /** Nothing listens: an embedded inspector can take the port. */
        FREE,
        /** Something else listens, or didn't answer in time. */
        OTHER,
        INSPECTOR
    }

    /**
     * @param embedded the inspector runs inside an application
     * @param pid      the process it runs in
     */
    record Ping(State state, boolean embedded, long pid) {

        static Ping of(State state) {
            return new Ping(state, false, -1);
        }

        /**
         * A standalone inspector, or one embedded in this JVM (found again after a DevTools
         * restart). Another app's embedded inspector is not used: it stops when that app does.
         */
        boolean usable() {
            return state == State.INSPECTOR && (!embedded || pid == ProcessHandle.current().pid());
        }
    }

    private record RouteRequest(String route, String upstream, String app) {
    }

    private final String url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    InspectorConnection(String url) {
        this.url = url;
    }

    /**
     * Only a server that identifies itself as the inspector counts, so traffic is never routed
     * to some other service that happens to listen on the same port.
     */
    Ping ping() {
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/ping")).timeout(Duration.ofMillis(500)).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.statusCode() == 200 ? json.readTree(response.body()) : null;
            if (body == null || !"agent-inspector".equals(body.path("name").asString(""))) {
                return Ping.of(State.OTHER);
            }
            JsonNode pid = body.path("pid");
            return new Ping(State.INSPECTOR, body.path("embedded").asBoolean(false), pid.isNumber() ? pid.longValue() : -1);
        } catch (ConnectException e) {
            return Ping.of(State.FREE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Ping.of(State.OTHER);
        } catch (Exception e) {
            return Ping.of(State.OTHER);
        }
    }

    boolean registerRoute(String route, String upstream, String app) {
        try {
            String body = json.writeValueAsString(new RouteRequest(route, upstream, app));
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
