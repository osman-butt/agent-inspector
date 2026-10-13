package io.github.osmanbutt.inspector.server.session;

import io.github.osmanbutt.inspector.server.api.ExchangeSummary;
import io.github.osmanbutt.inspector.server.capture.Exchange;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore;
import io.github.osmanbutt.inspector.server.chatclient.ChatClientCall;
import io.github.osmanbutt.inspector.server.chatclient.ChatClientCallStore;
import io.github.osmanbutt.inspector.server.proxy.RouteRegistry;
import io.github.osmanbutt.inspector.server.tools.ToolExecutionStore;
import io.github.osmanbutt.inspector.server.wire.ChatMessage;
import io.github.osmanbutt.inspector.server.wire.ParsedExchange;
import io.github.osmanbutt.inspector.server.wire.Part;
import io.github.osmanbutt.inspector.server.wire.WireParsers;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds session summaries and timelines from the stored exchanges. */
@Component
public class SessionViews {

    private final SessionTracker tracker;
    private final ExchangeStore store;
    private final WireParsers parsers;
    private final ToolExecutionStore toolExecutions;
    private final ChatClientCallStore chatClientCalls;
    private final RouteRegistry routes;

    public SessionViews(SessionTracker tracker, ExchangeStore store, WireParsers parsers,
                        ToolExecutionStore toolExecutions, ChatClientCallStore chatClientCalls, RouteRegistry routes) {
        this.tracker = tracker;
        this.store = store;
        this.parsers = parsers;
        this.toolExecutions = toolExecutions;
        this.chatClientCalls = chatClientCalls;
        this.routes = routes;
    }

    private record Round(Exchange exchange, ParsedExchange parsed) {
    }

    /** Newest first. Sessions whose exchanges were all evicted from the store are left out. */
    public List<SessionSummary> summaries() {
        return tracker.sessionIds().stream().map(this::summary).flatMap(Optional::stream).toList();
    }

    public Optional<SessionSummary> summary(String sessionId) {
        List<Round> rounds = rounds(sessionId);
        return rounds.isEmpty() ? Optional.empty() : Optional.of(summarize(sessionId, rounds));
    }

    public Optional<SessionDetail> detail(String sessionId) {
        List<Round> rounds = rounds(sessionId);
        if (rounds.isEmpty()) {
            return Optional.empty();
        }
        List<SessionDetail.Turn> turns = new ArrayList<>();
        Map<String, ChatClientCall> calls = new LinkedHashMap<>();
        for (Round round : rounds) {
            Optional<ChatClientCall> call = chatClientCall(round.exchange());
            call.ifPresent(found -> calls.putIfAbsent(found.id(), found));
            int resent = tracker.placement(round.exchange().id()).map(SessionTracker.Placement::newFrom).orElse(0);
            List<ChatMessage> messages = round.parsed().messages();
            turns.add(new SessionDetail.Turn(
                    ExchangeSummary.from(round.exchange(), round.parsed()),
                    resent,
                    messages.subList(Math.min(resent, messages.size()), messages.size()),
                    round.parsed().output(),
                    round.parsed().finishReason(),
                    round.parsed().error(),
                    call.map(ChatClientCall::id).orElse(null)));
        }
        List<String> toolCallIds = rounds.stream().flatMap(round -> round.parsed().toolCallIds().stream()).toList();
        return Optional.of(new SessionDetail(summarize(sessionId, rounds), turns,
                toolExecutions.forToolCalls(toolCallIds), List.copyOf(calls.values())));
    }

    /**
     * Model requests carry no trace of the ChatClient call that made them, so they are matched
     * by app and time: the call of the app that owns the route which was running when the
     * request went out.
     */
    private Optional<ChatClientCall> chatClientCall(Exchange exchange) {
        return routes.app(exchange.route()).flatMap(app -> chatClientCalls.runningAt(app, exchange.startedAt()));
    }

    private List<Round> rounds(String sessionId) {
        List<Round> rounds = new ArrayList<>();
        for (String exchangeId : tracker.exchangeIds(sessionId)) {
            store.findById(exchangeId).ifPresent(exchange -> parsers.parse(exchange)
                    .ifPresent(parsed -> rounds.add(new Round(exchange, parsed))));
        }
        return rounds;
    }

    private static SessionSummary summarize(String sessionId, List<Round> rounds) {
        Round first = rounds.getFirst();
        Round last = rounds.getLast();
        boolean pending = last.exchange().response() == null;
        Long durationMs = pending ? null : Duration.between(first.exchange().startedAt(),
                last.exchange().startedAt().plusMillis(last.exchange().response().durationMs())).toMillis();
        int toolCalls = 0;
        Integer inputTokens = null;
        Integer outputTokens = null;
        boolean failed = false;
        for (Round round : rounds) {
            ParsedExchange parsed = round.parsed();
            if (parsed.output() != null) {
                toolCalls += (int) parsed.output().parts().stream().filter(Part.ToolCall.class::isInstance).count();
            }
            if (parsed.usage() != null) {
                inputTokens = add(inputTokens, parsed.usage().inputTokens());
                outputTokens = add(outputTokens, parsed.usage().outputTokens());
            }
            failed |= parsed.error() != null;
        }
        return new SessionSummary(sessionId, first.exchange().route(), title(first.parsed()), last.parsed().model(),
                first.exchange().startedAt(), durationMs, rounds.size(), toolCalls, inputTokens, outputTokens,
                pending, failed);
    }

    /**
     * The question that started the session: the last user message of its first request. With
     * chat memory the earlier turns of a conversation come first, so the first user message
     * would name an older question.
     */
    private static String title(ParsedExchange parsed) {
        return parsed.messages().reversed().stream()
                .filter(message -> "user".equals(message.role()))
                .flatMap(message -> message.parts().stream())
                .filter(Part.Text.class::isInstance)
                .map(part -> ((Part.Text) part).text().strip())
                .findFirst()
                .map(text -> text.length() > 120 ? text.substring(0, 120) + "…" : text)
                .orElse("(no user message)");
    }

    private static Integer add(Integer total, Integer value) {
        if (value == null) {
            return total;
        }
        return total == null ? value : total + value;
    }
}
