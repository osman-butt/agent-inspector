package ek.osnb.inspector.server.wire;

import ek.osnb.inspector.server.capture.Exchange;

/** Understands one provider's wire format. */
public interface WireParser {

    boolean supports(Exchange exchange);

    /** Parses what is there so far: a pending exchange has a request but no response yet. */
    ParsedExchange parse(Exchange exchange);

    /** The proxy's error (unreachable, broken stream), else the provider's message, else the HTTP status. */
    static String errorMessage(Exchange.Response response, String providerError) {
        if (response.error() != null) {
            return response.error();
        }
        if (providerError != null) {
            return providerError;
        }
        return response.status() >= 400 ? "HTTP " + response.status() : null;
    }
}
