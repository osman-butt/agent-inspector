package ek.osnb.inspector.server.live;

import ek.osnb.inspector.server.api.ExchangeSummary;
import ek.osnb.inspector.server.capture.ExchangeStore.ExchangeSaved;
import ek.osnb.inspector.server.capture.ExchangeStore.StoreCleared;
import ek.osnb.inspector.server.wire.WireParsers;
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
 * with the summary of each new or completed exchange, and a {@code cleared} event.
 */
@RestController
public class LiveUpdates {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final WireParsers parsers;

    public LiveUpdates(WireParsers parsers) {
        this.parsers = parsers;
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
    void onCleared(StoreCleared cleared) {
        broadcast(SseEmitter.event().name("cleared").data(""));
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
