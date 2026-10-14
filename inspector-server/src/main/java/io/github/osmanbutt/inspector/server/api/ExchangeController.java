package io.github.osmanbutt.inspector.server.api;

import io.github.osmanbutt.inspector.server.capture.ExchangeStore;
import io.github.osmanbutt.inspector.server.proxy.RouteRegistry;
import io.github.osmanbutt.inspector.server.session.SessionTracker;
import io.github.osmanbutt.inspector.server.tools.McpToolCatalog;
import io.github.osmanbutt.inspector.server.tools.ToolExecutionStore;
import io.github.osmanbutt.inspector.server.wire.ParsedExchange;
import io.github.osmanbutt.inspector.server.wire.WireParsers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exchanges")
public class ExchangeController {

    private final ExchangeStore store;
    private final WireParsers parsers;
    private final SessionTracker sessions;
    private final ToolExecutionStore toolExecutions;
    private final RouteRegistry routes;
    private final McpToolCatalog mcpTools;

    public ExchangeController(ExchangeStore store, WireParsers parsers, SessionTracker sessions,
                              ToolExecutionStore toolExecutions, RouteRegistry routes, McpToolCatalog mcpTools) {
        this.store = store;
        this.parsers = parsers;
        this.sessions = sessions;
        this.toolExecutions = toolExecutions;
        this.routes = routes;
        this.mcpTools = mcpTools;
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
                .map(exchange -> {
                    ParsedExchange parsed = parsers.parse(exchange).orElse(null);
                    return ExchangeDetail.of(exchange, parsed, sessions.placement(id).orElse(null),
                            parsed == null ? Map.of() : toolExecutions.forToolCalls(parsed.toolCallIds()),
                            routes.app(exchange.route()).map(mcpTools::forApp).orElse(Map.of()));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No exchange " + id));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        store.clear();
    }
}
