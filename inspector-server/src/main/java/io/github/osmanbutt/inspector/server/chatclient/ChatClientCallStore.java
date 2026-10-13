package io.github.osmanbutt.inspector.server.chatclient;

import io.github.osmanbutt.inspector.server.InspectorProperties;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore.StoreCleared;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Recent ChatClient calls reported by instrumented applications. */
@Component
public class ChatClientCallStore {

    public record ChatClientCallSaved(ChatClientCall call) {
    }

    private final LinkedHashMap<String, ChatClientCall> calls;
    private final ApplicationEventPublisher events;

    public ChatClientCallStore(InspectorProperties properties, ApplicationEventPublisher events) {
        int capacity = properties.maxExchanges();
        this.calls = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ChatClientCall> eldest) {
                return size() > capacity;
            }
        };
        this.events = events;
    }

    public void save(ChatClientCall call) {
        synchronized (this) {
            calls.put(call.id(), call);
        }
        events.publishEvent(new ChatClientCallSaved(call));
    }

    /**
     * The call of {@code app} that was running when a model request went out at {@code time}.
     * When calls are nested (a sub-agent), the innermost one, which started last, wins.
     */
    public synchronized Optional<ChatClientCall> runningAt(String app, Instant time) {
        return calls.values().stream()
                .filter(call -> call.app().equals(app))
                .filter(call -> !time.isBefore(call.startedAt()) && !time.isAfter(call.endedAt()))
                .max(Comparator.comparing(ChatClientCall::startedAt));
    }

    /** Newest first. */
    public synchronized List<ChatClientCall> findAll() {
        return new ArrayList<>(calls.sequencedValues().reversed());
    }

    @EventListener
    synchronized void onCleared(StoreCleared cleared) {
        calls.clear();
    }
}
