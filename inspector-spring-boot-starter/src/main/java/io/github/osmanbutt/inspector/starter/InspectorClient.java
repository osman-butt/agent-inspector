package io.github.osmanbutt.inspector.starter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends reports to the inspector without getting in the application's way: posts happen
 * in order on one background thread, nothing ever throws, and after a failed post
 * reporting pauses for a few seconds, so a stopped inspector costs almost nothing.
 */
public class InspectorClient implements AutoCloseable {

    static final Duration PAUSE_AFTER_FAILURE = Duration.ofSeconds(5);

    private static final Log log = LogFactory.getLog(InspectorClient.class);

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-inspector-sender");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long pausedUntil;

    public InspectorClient(String url) {
        this.url = url;
    }

    /** Serializes the payload now and posts it in the background. */
    public void post(String path, Object payload) {
        if (System.currentTimeMillis() < pausedUntil) {
            return;
        }
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.debug("Could not serialize report for " + path, e);
            return;
        }
        sender.execute(() -> send(path, body));
    }

    private void send(String path, String body) {
        try {
            http.send(HttpRequest.newBuilder(URI.create(url + path))
                            .timeout(Duration.ofSeconds(2))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            pausedUntil = System.currentTimeMillis() + PAUSE_AFTER_FAILURE.toMillis();
            log.debug("Agent Inspector unreachable at " + url + ", pausing reports", e);
        }
    }

    @Override
    public void close() {
        sender.shutdown();
    }
}
