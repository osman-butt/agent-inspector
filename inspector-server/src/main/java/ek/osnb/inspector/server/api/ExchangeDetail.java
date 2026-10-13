package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.wire.ParsedExchange;

import java.time.Instant;

/** The raw exchange plus, when its wire format is known, the parsed conversation. */
public record ExchangeDetail(
        String id,
        String route,
        Instant startedAt,
        Exchange.Request request,
        Exchange.Response response,
        ParsedExchange parsed) {

    static ExchangeDetail of(Exchange exchange, ParsedExchange parsed) {
        return new ExchangeDetail(exchange.id(), exchange.route(), exchange.startedAt(),
                exchange.request(), exchange.response(), parsed);
    }
}
