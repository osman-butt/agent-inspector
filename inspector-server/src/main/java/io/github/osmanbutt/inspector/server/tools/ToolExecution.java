package io.github.osmanbutt.inspector.server.tools;

import java.time.Instant;

/**
 * A tool run inside the application, as reported by the starter. The wire shows that the
 * model asked for a tool and what the app sent back; this adds how long it took and
 * whether it failed. Linked to the model's tool call by {@code toolCallId}.
 *
 * @param error set when the tool threw, even if the app then sent the message to the model as a result
 */
public record ToolExecution(
        String app,
        String toolCallId,
        String name,
        String arguments,
        String result,
        String error,
        Instant startedAt,
        long durationMs) {
}
