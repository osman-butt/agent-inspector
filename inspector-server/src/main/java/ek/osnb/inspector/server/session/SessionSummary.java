package ek.osnb.inspector.server.session;

import java.time.Instant;

/**
 * One row in the session list.
 *
 * @param title      the first user message, shortened
 * @param durationMs from the first request to the last answer; {@code null} while running
 * @param toolCalls  how many tool calls the model made over the whole session
 */
public record SessionSummary(
        String id,
        String route,
        String title,
        String model,
        Instant startedAt,
        Long durationMs,
        int roundTrips,
        int toolCalls,
        Integer inputTokens,
        Integer outputTokens,
        boolean pending,
        boolean failed) {
}
