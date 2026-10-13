package io.github.osmanbutt.inspector.server.capture;

import io.github.osmanbutt.inspector.server.InspectorProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the most recent exchanges in memory. Saving an exchange with a known id
 * replaces it, so a pending exchange is completed in place. Every change is published
 * as an application event, so the UI can follow along live.
 */
@Component
public class ExchangeStore {

    public record ExchangeSaved(Exchange exchange) {
    }

    public record StoreCleared() {
    }

    private final LinkedHashMap<String, Exchange> exchanges;
    private final ApplicationEventPublisher events;

    public ExchangeStore(InspectorProperties properties, ApplicationEventPublisher events) {
        int capacity = properties.maxExchanges();
        this.exchanges = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Exchange> eldest) {
                return size() > capacity;
            }
        };
        this.events = events;
    }

    public void save(Exchange exchange) {
        synchronized (this) {
            exchanges.put(exchange.id(), exchange);
        }
        events.publishEvent(new ExchangeSaved(exchange));
    }

    public synchronized Optional<Exchange> findById(String id) {
        return Optional.ofNullable(exchanges.get(id));
    }

    /** Newest first. */
    public synchronized List<Exchange> findAll() {
        return new ArrayList<>(exchanges.sequencedValues().reversed());
    }

    public void clear() {
        synchronized (this) {
            exchanges.clear();
        }
        events.publishEvent(new StoreCleared());
    }
}
