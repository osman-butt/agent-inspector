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
 * @param toolCalls     names of the tools the model asked for in its answer
 * @param requestBytes  size of the stored request body in UTF-8
 * @param responseBytes size of the stored response body in UTF-8, {@code null} while running
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
        List<String> toolCalls,
        long requestBytes,
        Long responseBytes) {

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
                toolCalls,
                utf8Length(exchange.request().body()),
                response == null ? null : utf8Length(response.body()));
    }

    /** Counts without copying the body, which can be megabytes. */
    static long utf8Length(String text) {
        if (text == null) {
            return 0;
        }
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
