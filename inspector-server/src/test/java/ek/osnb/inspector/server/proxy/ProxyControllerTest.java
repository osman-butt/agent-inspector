package ek.osnb.inspector.server.proxy;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.capture.ExchangeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyControllerTest {

    static final FakeUpstream upstream = FakeUpstream.start();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("agent.inspector.routes.fake", upstream::baseUrl);
        registry.add("agent.inspector.routes.dead", () -> "http://127.0.0.1:1");
    }

    @LocalServerPort
    int port;

    @Autowired
    ExchangeStore store;

    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void clearStore() {
        store.clear();
    }

    @Test
    void forwardsTheCallAndRecordsBothDirections() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(proxy("/fake/echo/chat?stream=false"))
                        .header("Authorization", "Bearer sk-secret-1234567890")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"hello\":\"world\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Upstream")).hasValue("fake");
        assertThat(response.body())
                .contains("method=POST", "path=/base/echo/chat", "query=stream=false",
                        "authorization=Bearer sk-secret-1234567890", "body={\"hello\":\"world\"}");

        List<Exchange> exchanges = store.findAll();
        assertThat(exchanges).hasSize(1);
        Exchange exchange = exchanges.getFirst();
        assertThat(exchange.route()).isEqualTo("fake");
        assertThat(exchange.request().method()).isEqualTo("POST");
        assertThat(exchange.request().path()).isEqualTo("/echo/chat");
        assertThat(exchange.request().url()).isEqualTo(upstream.baseUrl() + "/echo/chat?stream=false");
        assertThat(exchange.request().body()).isEqualTo("{\"hello\":\"world\"}");
        assertThat(exchange.request().headers().get("authorization"))
                .doesNotContain("1234567890")
                .endsWith("(redacted)");
        assertThat(exchange.response().status()).isEqualTo(200);
        assertThat(exchange.response().body()).isEqualTo(response.body());
        assertThat(exchange.response().error()).isNull();
    }

    @Test
    void recordsButDoesNotForwardTheSessionHeader() throws Exception {
        client.send(HttpRequest.newBuilder(proxy("/fake/echo"))
                        .header("X-Inspector-Session", "my-run")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        Exchange exchange = store.findAll().getFirst();
        assertThat(exchange.request().headers()).containsEntry("x-inspector-session", "my-run");
        assertThat(FakeUpstream.lastHeaders.containsKey("X-inspector-session")).isFalse();
    }

    @Test
    void passesStreamedResponsesThrough() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(proxy("/fake/stream")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/event-stream");
        assertThat(response.body()).isEqualTo(FakeUpstream.STREAM);
        assertThat(store.findAll().getFirst().response().body()).isEqualTo(FakeUpstream.STREAM);
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(proxy("/nope/chat")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void unreachableUpstreamIsBadGatewayAndRecorded() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(proxy("/dead/chat")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(502);
        Exchange.Response recorded = store.findAll().getFirst().response();
        assertThat(recorded.status()).isEqualTo(502);
        assertThat(recorded.error()).contains("Could not reach");
    }

    private URI proxy(String path) {
        return URI.create("http://127.0.0.1:" + port + "/proxy" + path);
    }
}
