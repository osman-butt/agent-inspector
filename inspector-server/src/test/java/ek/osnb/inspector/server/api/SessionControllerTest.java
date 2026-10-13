package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.capture.ExchangeStore;
import ek.osnb.inspector.server.session.SessionTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.Map;

import static ek.osnb.inspector.server.wire.Fixtures.pending;
import static ek.osnb.inspector.server.wire.Fixtures.read;
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
    void unknownSessionIsNotFound() {
        assertThat(mvc.get().uri("/api/sessions/nope")).hasStatus(404);
    }

    private Exchange save(Exchange request, String responseBody) {
        store.save(request);
        store.save(request.complete(new Exchange.Response(200, Map.of(), responseBody, 100, null)));
        return request;
    }
}
