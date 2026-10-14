package io.github.osmanbutt.inspector.server.api;

import io.github.osmanbutt.inspector.server.capture.Exchange;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore;
import io.github.osmanbutt.inspector.server.proxy.RouteRegistry;
import io.github.osmanbutt.inspector.server.session.SessionTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static io.github.osmanbutt.inspector.server.wire.Fixtures.pending;
import static io.github.osmanbutt.inspector.server.wire.Fixtures.read;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class SessionControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ExchangeStore store;

    @Autowired
    SessionTracker tracker;

    @Autowired
    RouteRegistry routes;

    String sessionId;

    @BeforeEach
    void recordAToolLoop() {
        store.clear();
        save(pending("dmr", "/chat/completions", read("openai/tool-call.request.json"), Map.of()),
                read("openai/tool-call.response.json"));
        Exchange second = save(pending("dmr", "/chat/completions", read("openai/tool-result.request.json"), Map.of()),
                read("openai/tool-result.response.json"));
        sessionId = tracker.placement(second.id()).orElseThrow().sessionId();
    }

    @Test
    void listsSessions() {
        assertThat(mvc.get().uri("/api/sessions"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        [{"id": "%s", "route": "dmr",
                          "title": "What is the weather in Copenhagen and what time is it there?",
                          "model": "ai/qwen3:4b-instruct-2507-q4_K_M",
                          "roundTrips": 2, "toolCalls": 2, "inputTokens": 728, "outputTokens": 75,
                          "pending": false, "failed": false}]
                        """.formatted(sessionId));
    }

    @Test
    void showsEachRoundTripWithOnlyItsNewMessages() {
        assertThat(mvc.get().uri("/api/sessions/{id}", sessionId))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"turns": [
                          {"resent": 0, "finishReason": "tool_calls",
                           "newMessages": [{"role": "system"}, {"role": "user"}],
                           "output": {"parts": [{"type": "tool_call", "name": "weather"}, {"type": "tool_call", "name": "currentTime"}]}},
                          {"resent": 3, "finishReason": "stop",
                           "newMessages": [{"role": "tool"}, {"role": "tool"}],
                           "output": {"parts": [{"type": "text"}]}}
                        ]}
                        """);
    }

    @Test
    void exchangeDetailKnowsItsSession() {
        String exchangeId = tracker.exchangeIds(sessionId).getLast();
        assertThat(mvc.get().uri("/api/exchanges/{id}", exchangeId))
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"session": {"sessionId": "%s", "turn": 2, "newFrom": 3}}
                        """.formatted(sessionId));
    }

    @Test
    void reportedToolExecutionsAreLinkedByToolCallId() {
        assertThat(mvc.post().uri("/api/tool-executions").contentType("application/json").content("""
                {"app": "inspector-sample", "toolCallId": "giiqHefFwpjJ8nG4xulRxj0Nf8mfkWxi", "name": "currentTime",
                 "arguments": "{\\"zone\\": \\"Europe/Copenhagen\\"}", "result": "\\"2026-10-13T16:44:27+02:00\\"",
                 "startedAt": "2026-10-13T14:44:27Z", "durationMs": 3}
                """)).hasStatus(202);

        assertThat(mvc.get().uri("/api/sessions/{id}", sessionId))
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"toolExecutions": {"giiqHefFwpjJ8nG4xulRxj0Nf8mfkWxi": {"name": "currentTime", "durationMs": 3}}}
                        """);
        String firstRoundTrip = tracker.exchangeIds(sessionId).getFirst();
        assertThat(mvc.get().uri("/api/exchanges/{id}", firstRoundTrip))
                .bodyJson()
                .extractingPath("$.toolExecutions.giiqHefFwpjJ8nG4xulRxj0Nf8mfkWxi.app").isEqualTo("inspector-sample");
    }

    @Test
    void roundTripsAreLinkedToTheChatClientCallRunningInTheirApp() {
        routes.register("sample-openai", URI.create("http://localhost:12434/engines/v1"), "sample");
        Exchange first = save(pending("sample-openai", "/chat/completions", read("openai/tool-call.request.json"), Map.of()),
                read("openai/tool-call.response.json"));
        save(pending("sample-openai", "/chat/completions", read("openai/tool-result.request.json"), Map.of()),
                read("openai/tool-result.response.json"));
        String session = tracker.placement(first.id()).orElseThrow().sessionId();

        assertThat(mvc.post().uri("/api/chat-client-calls").contentType("application/json").content("""
                {"id": "call-1", "app": "sample", "startedAt": "%s", "durationMs": 60000, "stream": false,
                 "messages": [{"role": "user", "text": "What is the weather in Copenhagen?"}],
                 "advisors": [{"name": "MessageChatMemoryAdvisor", "order": -2147482648, "durationMs": 1200, "messages": []},
                              {"name": "ChatModelCallAdvisor", "order": 2147483647, "durationMs": 1190, "messages": []}],
                 "answer": "Windy, 1°C."}
                """.formatted(Instant.now().minusSeconds(5)))).hasStatus(202);

        assertThat(mvc.get().uri("/api/sessions/{id}", session))
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"turns": [{"chatClientCallId": "call-1"}, {"chatClientCallId": "call-1"}],
                         "chatClientCalls": [{"id": "call-1", "advisors": [{"name": "MessageChatMemoryAdvisor"}, {"name": "ChatModelCallAdvisor"}]}]}
                        """);
        // the dmr route was not registered by an app, so its session has no ChatClient call
        assertThat(mvc.get().uri("/api/sessions/{id}", sessionId))
                .bodyJson()
                .extractingPath("$.chatClientCalls").asArray().isEmpty();
    }

    @Test
    void aTextPartWithoutTextDoesNotBreakSessions() {
        store.clear();
        save(pending("dmr", "/chat/completions", """
                {"model": "ai/qwen3", "messages": [{"role": "user", "content": [{"type": "text"}]}]}
                """, Map.of()), read("openai/tool-result.response.json"));

        assertThat(mvc.get().uri("/api/sessions"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[0].title").isEqualTo("(no user message)");
    }

    @Test
    void unknownSessionIsNotFound() {
        assertThat(mvc.get().uri("/api/sessions/nope")).hasStatus(404);
    }

    private Exchange save(Exchange request, String responseBody) {
        store.save(request);
        store.save(request.complete(new Exchange.Response(200, Map.of(), responseBody, 100, null)));
        return request;
    }
}
