package io.github.osmanbutt.inspector.starter;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.time.Duration;
import java.time.Instant;

/**
 * Reports every tool execution (including MCP tools) from the observation Spring AI already
 * emits around each tool call: name, arguments, result, duration and failure.
 */
public class ToolExecutionObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

    /** What the inspector receives, matched to the model's tool call by {@code toolCallId}. */
    public record ToolExecutionReport(String app, String toolCallId, String name, String arguments, String result,
                                      String error, Instant startedAt, long durationMs) {
    }

    private record Started(Instant at, long nanos) {
    }

    static final int MAX_RESULT_LENGTH = 20_000;

    private final InspectorClient client;
    private final String app;

    public ToolExecutionObservationHandler(InspectorClient client, String app) {
        this.client = client;
        this.app = app;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStart(ToolCallingObservationContext context) {
        if (!isRepeat(context)) {
            context.put(Started.class, new Started(Instant.now(), System.nanoTime()));
        }
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        Started started = context.get(Started.class);
        if (started == null) {
            return;
        }
        String name = context.getToolDefinition().name();
        ToolExecutionException failure = ToolFailures.take(name);
        Throwable error = context.getError() != null ? context.getError() : failure;
        client.post("/api/tool-executions", new ToolExecutionReport(app, context.getToolCallId(), name,
                context.getToolCallArguments(), truncate(context.getToolCallResult()), describe(error),
                started.at(), Duration.ofNanos(System.nanoTime() - started.nanos()).toMillis()));
    }

    /** Some tools (MCP tools, for one) are observed again inside their own observation: report the outer one only. */
    static boolean isRepeat(ToolCallingObservationContext context) {
        ObservationView parent = context.getParentObservation();
        while (parent != null) {
            if (parent.getContextView() instanceof ToolCallingObservationContext outer
                    && outer.getToolCallId() != null && outer.getToolCallId().equals(context.getToolCallId())) {
                return true;
            }
            parent = parent.getContextView() instanceof Observation.Context outer ? outer.getParentObservation() : null;
        }
        return false;
    }

    /** The root cause tells most: a ToolExecutionException only wraps what the tool threw. */
    private static String describe(Throwable error) {
        if (error == null) {
            return null;
        }
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_RESULT_LENGTH ? text : text.substring(0, MAX_RESULT_LENGTH) + "…";
    }
}
