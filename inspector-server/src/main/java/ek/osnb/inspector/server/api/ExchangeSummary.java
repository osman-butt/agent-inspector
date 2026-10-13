package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.wire.ParsedExchange;
import ek.osnb.inspector.server.wire.Part;

import java.time.Instant;
import java.util.List;

/**
 * One row in the exchange list. Status and duration are {@code null} while the call is
 * running; the model fields are {@code null} when the wire format isn't known.
 *
 * @param toolCalls names of the tools the model asked for in its answer
 */
public record ExchangeSummary(
        String id,
        String route,
        Instant startedAt,
        String method,
        String path,
        Integer status,
        Long durationMs,
        String error,
        String provider,
        String model,
        boolean streamed,
        Integer inputTokens,
        Integer outputTokens,
        List<String> toolCalls) {

    public static ExchangeSummary from(Exchange exchange, ParsedExchange parsed) {
        Exchange.Response response = exchange.response();
        ParsedExchange.Usage usage = parsed == null ? null : parsed.usage();
        List<String> toolCalls = parsed == null || parsed.output() == null ? List.of() : parsed.output().parts().stream()
                .filter(Part.ToolCall.class::isInstance)
                .map(part -> ((Part.ToolCall) part).name())
                .toList();
        return new ExchangeSummary(
                exchange.id(),
                exchange.route(),
                exchange.startedAt(),
                exchange.request().method(),
                exchange.request().path(),
                response == null ? null : response.status(),
                response == null ? null : response.durationMs(),
                parsed != null && parsed.error() != null ? parsed.error() : response == null ? null : response.error(),
                parsed == null ? null : parsed.provider(),
                parsed == null ? null : parsed.model(),
                parsed != null && parsed.streamed(),
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                toolCalls);
    }
}
