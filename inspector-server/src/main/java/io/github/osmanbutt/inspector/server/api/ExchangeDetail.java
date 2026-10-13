package io.github.osmanbutt.inspector.server.api;

import io.github.osmanbutt.inspector.server.capture.Exchange;
import io.github.osmanbutt.inspector.server.session.SessionTracker.Placement;
import io.github.osmanbutt.inspector.server.tools.ToolExecution;
import io.github.osmanbutt.inspector.server.wire.ParsedExchange;

import java.time.Instant;
import java.util.Map;

/**
 * The raw exchange plus, when its wire format is known, the parsed conversation, the
 * exchange's place in its session, and what the app reported about running the tools.
 *
 * @param toolExecutions by tool call id
 */
public record ExchangeDetail(
        String id,
        String route,
        Instant startedAt,
        Exchange.Request request,
        Exchange.Response response,
        ParsedExchange parsed,
        Placement session,
        Map<String, ToolExecution> toolExecutions) {

    static ExchangeDetail of(Exchange exchange, ParsedExchange parsed, Placement session,
                             Map<String, ToolExecution> toolExecutions) {
        return new ExchangeDetail(exchange.id(), exchange.route(), exchange.startedAt(),
                exchange.request(), exchange.response(), parsed, session, toolExecutions);
    }
}
