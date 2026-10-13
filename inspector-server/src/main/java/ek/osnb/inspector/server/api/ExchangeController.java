package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.capture.ExchangeStore;
import ek.osnb.inspector.server.session.SessionTracker;
import ek.osnb.inspector.server.wire.WireParsers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/exchanges")
public class ExchangeController {

    private final ExchangeStore store;
    private final WireParsers parsers;
    private final SessionTracker sessions;

    public ExchangeController(ExchangeStore store, WireParsers parsers, SessionTracker sessions) {
        this.store = store;
        this.parsers = parsers;
        this.sessions = sessions;
    }

    /** Newest first. */
    @GetMapping
    public List<ExchangeSummary> list() {
        return store.findAll().stream()
                .map(exchange -> ExchangeSummary.from(exchange, parsers.parse(exchange).orElse(null)))
                .toList();
    }

    @GetMapping("/{id}")
    public ExchangeDetail get(@PathVariable String id) {
        return store.findById(id)
                .map(exchange -> ExchangeDetail.of(exchange, parsers.parse(exchange).orElse(null),
                        sessions.placement(id).orElse(null)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No exchange " + id));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        store.clear();
    }
}
