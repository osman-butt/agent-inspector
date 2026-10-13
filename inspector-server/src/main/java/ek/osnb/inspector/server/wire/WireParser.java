package ek.osnb.inspector.server.wire;

import ek.osnb.inspector.server.capture.Exchange;

/** Understands one provider's wire format. */
public interface WireParser {

    boolean supports(Exchange exchange);

    /** Parses what is there so far: a pending exchange has a request but no response yet. */
    ParsedExchange parse(Exchange exchange);
}
