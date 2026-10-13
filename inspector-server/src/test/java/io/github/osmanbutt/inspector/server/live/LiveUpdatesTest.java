package io.github.osmanbutt.inspector.server.live;

import io.github.osmanbutt.inspector.server.capture.Exchange;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveUpdatesTest {

    @LocalServerPort
    int port;

    @Autowired
    ExchangeStore store;

    @Test
    void streamsSavedExchangesAndClears() throws Exception {
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/stream")).build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        try (Stream<String> body = response.body()) {
            Iterator<String> lines = body.iterator();
            assertThat(lines.next()).isEqualTo(":connected");

            Exchange exchange = Exchange.start("dmr",
                    new Exchange.Request("POST", "http://upstream/chat/completions", "/chat/completions", Map.of(), "{}"));
            store.save(exchange);
            assertThat(nextEvent(lines)).startsWith("event:exchange\ndata:{\"id\":\"" + exchange.id() + "\"");
            assertThat(nextEvent(lines)).startsWith("event:session\ndata:{");

            store.clear();
            assertThat(nextEvent(lines)).startsWith("event:cleared");
        }
    }

    /** The next SSE event as its lines joined with newlines, read with a timeout so a missing event fails fast. */
    private static String nextEvent(Iterator<String> lines) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder event = new StringBuilder();
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.isEmpty()) {
                    if (!event.isEmpty()) {
                        break;
                    }
                    continue;
                }
                event.append(event.isEmpty() ? "" : "\n").append(line);
            }
            return event.toString();
        }).get(5, TimeUnit.SECONDS);
    }
}
