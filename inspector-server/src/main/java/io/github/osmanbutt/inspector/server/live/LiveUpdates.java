package io.github.osmanbutt.inspector.server.live;

import io.github.osmanbutt.inspector.server.api.ExchangeSummary;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore.ExchangeSaved;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore.StoreCleared;
import io.github.osmanbutt.inspector.server.chatclient.ChatClientCallStore.ChatClientCallSaved;
import io.github.osmanbutt.inspector.server.session.SessionTracker.SessionUpdated;
import io.github.osmanbutt.inspector.server.session.SessionViews;
import io.github.osmanbutt.inspector.server.tools.ToolExecutionStore.ToolExecutionSaved;
import io.github.osmanbutt.inspector.server.wire.WireParsers;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes store changes to every open UI over server-sent events: an {@code exchange} event
 * with the summary of each new or completed exchange, a {@code session} event with the
 * summary of the session it belongs to, {@code tool} and {@code chat-client-call} events for
 * what the starter reports, and a {@code cleared} event.
 */
@RestController
public class LiveUpdates {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final WireParsers parsers;
    private final SessionViews sessions;

    public LiveUpdates(WireParsers parsers, SessionViews sessions) {
        this.parsers = parsers;
        this.sessions = sessions;
    }

    @GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() throws IOException {
        SseEmitter emitter = new SseEmitter(0L); // never time out; EventSource reconnects anyway
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        emitter.send(SseEmitter.event().comment("connected"));
        return emitter;
    }

    @EventListener
    void onSaved(ExchangeSaved saved) {
        ExchangeSummary summary = ExchangeSummary.from(saved.exchange(), parsers.parse(saved.exchange()).orElse(null));
        broadcast(SseEmitter.event().name("exchange").data(summary));
    }

    @EventListener
    void onSessionUpdated(SessionUpdated updated) {
        sessions.summary(updated.sessionId())
                .ifPresent(summary -> broadcast(SseEmitter.event().name("session").data(summary)));
    }

    @EventListener
    void onToolExecution(ToolExecutionSaved saved) {
        broadcast(SseEmitter.event().name("tool").data(saved.execution()));
    }

    @EventListener
    void onChatClientCall(ChatClientCallSaved saved) {
        broadcast(SseEmitter.event().name("chat-client-call").data(saved.call()));
    }

    @EventListener
    void onCleared(StoreCleared cleared) {
        broadcast(SseEmitter.event().name("cleared").data(""));
    }

    /** Open streams never finish on their own, and graceful shutdown would wait for them. */
    @EventListener
    void onShutdown(ContextClosedEvent closed) {
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    private void broadcast(SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter); // the browser went away
            }
        }
    }
}
