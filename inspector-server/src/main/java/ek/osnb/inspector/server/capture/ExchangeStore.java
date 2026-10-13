package ek.osnb.inspector.server.capture;

import ek.osnb.inspector.server.InspectorProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the most recent exchanges in memory. Saving an exchange with a known id
 * replaces it, so a pending exchange is completed in place.
 */
@Component
public class ExchangeStore {

    private final LinkedHashMap<String, Exchange> exchanges;

    public ExchangeStore(InspectorProperties properties) {
        int capacity = properties.maxExchanges();
        this.exchanges = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Exchange> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized void save(Exchange exchange) {
        exchanges.put(exchange.id(), exchange);
    }

    public synchronized Optional<Exchange> findById(String id) {
        return Optional.ofNullable(exchanges.get(id));
    }

    /** Newest first. */
    public synchronized List<Exchange> findAll() {
        return new ArrayList<>(exchanges.sequencedValues().reversed());
    }

    public synchronized void clear() {
        exchanges.clear();
    }
}
