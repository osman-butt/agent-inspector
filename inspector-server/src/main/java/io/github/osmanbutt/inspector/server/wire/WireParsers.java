package io.github.osmanbutt.inspector.server.wire;

import io.github.osmanbutt.inspector.server.InspectorProperties;
import io.github.osmanbutt.inspector.server.capture.Exchange;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore.StoreCleared;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the parser for an exchange and remembers the result, so the list and detail
 * views don't parse the same bodies again. A parse failure means "no parsed view",
 * never an error: the raw exchange is always there.
 */
@Component
public class WireParsers {

    private static final Logger log = LoggerFactory.getLogger(WireParsers.class);

    private record Cached(boolean complete, Optional<ParsedExchange> parsed) {
    }

    private final List<WireParser> parsers;
    private final Map<String, Cached> cache;

    public WireParsers(List<WireParser> parsers, InspectorProperties properties) {
        this.parsers = parsers;
        int capacity = properties.maxExchanges() * 2;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > capacity;
            }
        };
    }

    public Optional<ParsedExchange> parse(Exchange exchange) {
        boolean complete = exchange.response() != null;
        synchronized (cache) {
            Cached cached = cache.get(exchange.id());
            if (cached != null && cached.complete() == complete) {
                return cached.parsed();
            }
        }
        Optional<ParsedExchange> parsed = parsers.stream()
                .filter(parser -> parser.supports(exchange))
                .findFirst()
                .map(parser -> tryParse(parser, exchange));
        synchronized (cache) {
            cache.put(exchange.id(), new Cached(complete, parsed));
        }
        return parsed;
    }

    private static ParsedExchange tryParse(WireParser parser, Exchange exchange) {
        try {
            return parser.parse(exchange);
        } catch (RuntimeException e) {
            log.debug("Could not parse exchange {} with {}", exchange.id(), parser.getClass().getSimpleName(), e);
            return null;
        }
    }

    @EventListener
    void onCleared(StoreCleared cleared) {
        synchronized (cache) {
            cache.clear();
        }
    }
}
