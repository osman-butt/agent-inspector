package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.capture.Exchange;

import java.time.Instant;

/** One row in the exchange list. Status and duration are {@code null} while the call is running. */
public record ExchangeSummary(
        String id,
        String route,
        Instant startedAt,
        String method,
        String path,
        Integer status,
        Long durationMs,
        String error) {

    public static ExchangeSummary from(Exchange exchange) {
        Exchange.Response response = exchange.response();
        return new ExchangeSummary(
                exchange.id(),
                exchange.route(),
                exchange.startedAt(),
                exchange.request().method(),
                exchange.request().path(),
                response == null ? null : response.status(),
                response == null ? null : response.durationMs(),
                response == null ? null : response.error());
    }
}
