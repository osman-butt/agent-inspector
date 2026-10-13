package io.github.osmanbutt.inspector.server.session;

import io.github.osmanbutt.inspector.server.InspectorProperties;
import io.github.osmanbutt.inspector.server.capture.Exchange;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore.ExchangeSaved;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore.StoreCleared;
import io.github.osmanbutt.inspector.server.proxy.ProxyController;
import io.github.osmanbutt.inspector.server.wire.ChatMessage;
import io.github.osmanbutt.inspector.server.wire.ParsedExchange;
import io.github.osmanbutt.inspector.server.wire.Part;
import io.github.osmanbutt.inspector.server.wire.WireParsers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Groups round-trips into sessions. Model APIs are stateless, so an agent loop re-sends the
 * whole conversation every time: a request whose messages start with an earlier
 * round-trip's messages plus that round-trip's answer continues the same session.
 * An {@code X-Inspector-Session} header on the request overrides the guess.
 */
@Component
public class SessionTracker {

    /**
     * Where an exchange sits in its session.
     *
     * @param turn    1 for the first round-trip of the session
     * @param newFrom index of the first request message that wasn't sent before
     */
    public record Placement(String sessionId, int turn, int newFrom) {
    }

    public record SessionUpdated(String sessionId) {
    }

    private static final class Session {
        final String id;
        final String route;
        final List<String> exchangeIds = new ArrayList<>();
        /** Fingerprints of the last request's messages plus its answer: what the next request should start with. */
        List<String> conversation = List.of();
        boolean waiting;

        Session(String id, String route) {
            this.id = id;
            this.route = route;
        }
    }

    private final WireParsers parsers;
    private final ApplicationEventPublisher events;
    private final LinkedHashMap<String, Session> sessions;
    private final Map<String, Placement> placements;

    public SessionTracker(WireParsers parsers, ApplicationEventPublisher events, InspectorProperties properties) {
        this.parsers = parsers;
        this.events = events;
        int capacity = properties.maxExchanges();
        this.sessions = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
                return size() > capacity;
            }
        };
        this.placements = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Placement> eldest) {
                return size() > capacity;
            }
        };
    }

    @EventListener
    void onSaved(ExchangeSaved saved) {
        Exchange exchange = saved.exchange();
        Optional<ParsedExchange> parsed = parsers.parse(exchange);
        if (parsed.isEmpty()) {
            return; // not a model call we understand (embeddings, model lists, ...)
        }
        String sessionId;
        synchronized (this) {
            Placement placement = placements.computeIfAbsent(exchange.id(), id -> place(exchange, parsed.get()));
            Session session = sessions.get(placement.sessionId());
            sessionId = placement.sessionId();
            if (session != null && exchange.id().equals(session.exchangeIds.getLast()) && exchange.response() != null) {
                List<ChatMessage> conversation = new ArrayList<>(parsed.get().messages());
                if (parsed.get().output() != null) {
                    conversation.add(parsed.get().output());
                }
                session.conversation = fingerprints(conversation);
                session.waiting = false;
            }
        }
        events.publishEvent(new SessionUpdated(sessionId));
    }

    private Placement place(Exchange exchange, ParsedExchange parsed) {
        List<String> request = fingerprints(parsed.messages());
        String explicit = exchange.request().headers().get(ProxyController.SESSION_HEADER);
        Session session = explicit != null ? sessions.get(explicit) : continued(exchange.route(), request);
        int newFrom = session == null ? 0 : commonPrefix(session.conversation, request);
        if (session == null) {
            session = new Session(explicit != null ? explicit : UUID.randomUUID().toString().substring(0, 8), exchange.route());
            sessions.put(session.id, session);
        }
        session.exchangeIds.add(exchange.id());
        session.conversation = request;
        session.waiting = true;
        return new Placement(session.id, session.exchangeIds.size(), newFrom);
    }

    /**
     * The session this request continues: same route, not waiting for an answer (an agent
     * sends its next request only after the last answer arrived), and its conversation so far
     * is a prefix of this request. The longest match wins, then the most recent.
     */
    private Session continued(String route, List<String> request) {
        Session best = null;
        for (Session session : sessions.sequencedValues().reversed()) {
            if (!session.route.equals(route) || session.waiting || session.conversation.isEmpty()
                    || session.conversation.size() > request.size()
                    || !request.subList(0, session.conversation.size()).equals(session.conversation)) {
                continue;
            }
            if (best == null || session.conversation.size() > best.conversation.size()) {
                best = session;
            }
        }
        return best;
    }

    private static int commonPrefix(List<String> previous, List<String> request) {
        int i = 0;
        while (i < previous.size() && i < request.size() && previous.get(i).equals(request.get(i))) {
            i++;
        }
        return i;
    }

    /**
     * What identifies a message when it is sent again. Thinking is left out because most
     * APIs don't expect it back, and text is trimmed because clients sometimes tidy it.
     */
    static List<String> fingerprints(List<ChatMessage> messages) {
        return messages.stream().map(SessionTracker::fingerprint).toList();
    }

    private static String fingerprint(ChatMessage message) {
        StringBuilder print = new StringBuilder(String.valueOf(message.role())).append('|');
        for (Part part : message.parts()) {
            switch (part) {
                case Part.Text text -> print.append("t:").append(text.text() == null ? "" : text.text().strip());
                case Part.ToolCall call -> print.append("c:").append(call.id() != null ? call.id() : call.name() + call.arguments());
                case Part.ToolResult result -> print.append("r:").append(result.toolCallId()).append(':').append(result.content());
                case Part.Other other -> print.append("o:").append(other.label());
                case Part.Thinking thinking -> {
                    continue;
                }
            }
            print.append('\n');
        }
        return print.toString();
    }

    public synchronized Optional<Placement> placement(String exchangeId) {
        return Optional.ofNullable(placements.get(exchangeId));
    }

    /** Session ids, newest first. */
    public synchronized List<String> sessionIds() {
        return sessions.sequencedKeySet().reversed().stream().toList();
    }

    public synchronized List<String> exchangeIds(String sessionId) {
        Session session = sessions.get(sessionId);
        return session == null ? List.of() : List.copyOf(session.exchangeIds);
    }

    @EventListener
    synchronized void onCleared(StoreCleared cleared) {
        sessions.clear();
        placements.clear();
    }
}
