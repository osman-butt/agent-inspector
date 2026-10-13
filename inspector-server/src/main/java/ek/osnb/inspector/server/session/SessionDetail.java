package ek.osnb.inspector.server.session;

import ek.osnb.inspector.server.api.ExchangeSummary;
import ek.osnb.inspector.server.wire.ChatMessage;

import java.util.List;

/** A session as a timeline of round-trips, each showing only what it added. */
public record SessionDetail(SessionSummary summary, List<Turn> turns) {

    /**
     * @param resent      how many request messages were sent before and are repeated here
     * @param newMessages the request messages after those
     * @param output      the model's answer, {@code null} while pending or on error
     */
    public record Turn(
            ExchangeSummary exchange,
            int resent,
            List<ChatMessage> newMessages,
            ChatMessage output,
            String finishReason,
            String error) {
    }
}
