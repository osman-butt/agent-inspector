package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.capture.ExchangeStore;
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

    public ExchangeController(ExchangeStore store) {
        this.store = store;
    }

    /** Newest first. */
    @GetMapping
    public List<ExchangeSummary> list() {
        return store.findAll().stream().map(ExchangeSummary::from).toList();
    }

    @GetMapping("/{id}")
    public Exchange get(@PathVariable String id) {
        return store.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No exchange " + id));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        store.clear();
    }
}
