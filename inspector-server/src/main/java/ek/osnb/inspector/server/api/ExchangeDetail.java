package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.session.SessionTracker.Placement;
import ek.osnb.inspector.server.tools.ToolExecution;
import ek.osnb.inspector.server.wire.ParsedExchange;

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
