package ek.osnb.inspector.server.session;

import ek.osnb.inspector.server.InspectorProperties;
import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.capture.ExchangeStore.ExchangeSaved;
import ek.osnb.inspector.server.session.SessionTracker.Placement;
import ek.osnb.inspector.server.wire.AnthropicMessagesParser;
import ek.osnb.inspector.server.wire.OpenAiChatParser;
import ek.osnb.inspector.server.wire.WireParsers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ek.osnb.inspector.server.wire.Fixtures.pending;
import static ek.osnb.inspector.server.wire.Fixtures.read;
import static org.assertj.core.api.Assertions.assertThat;

/** Uses the recorded tool loop: round-trip 1 asks for two tools, round-trip 2 sends their results. */
class SessionTrackerTest {

    final InspectorProperties properties = new InspectorProperties(null, 100, null);
    final SessionTracker tracker = new SessionTracker(
            new WireParsers(List.of(new OpenAiChatParser(), new AnthropicMessagesParser()), properties),
            event -> {
            }, properties);

    final String askForTools = read("openai/tool-call.request.json");
    final String toolsAsked = read("openai/tool-call.response.json");
    final String sendResults = read("openai/tool-result.request.json");
    final String answer = read("openai/tool-result.response.json");

    @Test
    void aToolLoopIsOneSession() {
        Placement first = run("dmr", askForTools, toolsAsked);
        Placement second = run("dmr", sendResults, answer);

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(first.turn()).isEqualTo(1);
        assertThat(first.newFrom()).isZero();
        assertThat(second.turn()).isEqualTo(2);
        // system, user and the assistant's tool calls were sent before; the two tool results are new
        assertThat(second.newFrom()).isEqualTo(3);
        assertThat(tracker.sessionIds()).hasSize(1);
    }

    @Test
    void theSameQuestionAskedAgainIsANewSession() {
        Placement first = run("dmr", askForTools, toolsAsked);
        Placement again = run("dmr", askForTools, toolsAsked);

        assertThat(again.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(tracker.sessionIds()).containsExactly(again.sessionId(), first.sessionId());
    }

    @Test
    void aSessionWaitingForItsAnswerIsNotContinued() {
        Exchange waiting = pending("dmr", "/chat/completions", askForTools, Map.of());
        tracker.onSaved(new ExchangeSaved(waiting));
        Placement concurrent = run("dmr", sendResults, answer);

        assertThat(concurrent.sessionId()).isNotEqualTo(placement(waiting).sessionId());
    }

    @Test
    void otherRoutesAreOtherSessions() {
        Placement dmr = run("dmr", askForTools, toolsAsked);
        Placement openai = run("openai", sendResults, answer);

        assertThat(openai.sessionId()).isNotEqualTo(dmr.sessionId());
    }

    @Test
    void theSessionHeaderWins() {
        Map<String, String> header = Map.of("x-inspector-session", "run-1");
        Placement first = run(pending("dmr", "/chat/completions", askForTools, header), toolsAsked);
        Placement unrelated = run(pending("openai", "/chat/completions", read("openai/stream-tool-call.request.json"), header), answer);

        assertThat(first.sessionId()).isEqualTo("run-1");
        assertThat(unrelated.sessionId()).isEqualTo("run-1");
        assertThat(unrelated.turn()).isEqualTo(2);
    }

    @Test
    void callsThatAreNotModelCallsAreNotPlaced() {
        Exchange embeddings = pending("openai", "/embeddings", "{\"input\": \"hello\"}", Map.of());
        tracker.onSaved(new ExchangeSaved(embeddings));

        assertThat(tracker.placement(embeddings.id())).isEmpty();
        assertThat(tracker.sessionIds()).isEmpty();
    }

    private Placement run(String route, String requestBody, String responseBody) {
        return run(pending(route, "/chat/completions", requestBody, Map.of()), responseBody);
    }

    /** Saves the exchange as the proxy does: once when the request goes out, once when the answer is in. */
    private Placement run(Exchange request, String responseBody) {
        tracker.onSaved(new ExchangeSaved(request));
        tracker.onSaved(new ExchangeSaved(request.complete(new Exchange.Response(200, Map.of(), responseBody, 100, null))));
        return placement(request);
    }

    private Placement placement(Exchange exchange) {
        return tracker.placement(exchange.id()).orElseThrow();
    }
}
