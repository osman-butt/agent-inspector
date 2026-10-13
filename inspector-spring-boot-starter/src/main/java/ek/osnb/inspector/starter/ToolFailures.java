package ek.osnb.inspector.starter;

import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * Spring AI catches a tool's exception inside the tool-call observation and sends its message
 * to the model as the result, so the observation itself never sees the failure. Wrapping the
 * {@link ToolExecutionExceptionProcessor} catches it on its way through; the observation
 * handler, which runs next on the same thread, picks it up.
 */
final class ToolFailures {

    private static final ThreadLocal<ToolExecutionException> LAST = new ThreadLocal<>();

    private ToolFailures() {
    }

    /** The failure recorded on this thread for the named tool, if any; clears it either way. */
    static ToolExecutionException take(String toolName) {
        ToolExecutionException failure = LAST.get();
        LAST.remove();
        if (failure == null || failure.getToolDefinition() == null
                || !failure.getToolDefinition().name().equals(toolName)) {
            return null;
        }
        return failure;
    }

    static final class Recording implements ToolExecutionExceptionProcessor {

        private final ToolExecutionExceptionProcessor delegate;

        Recording(ToolExecutionExceptionProcessor delegate) {
            this.delegate = delegate;
        }

        @Override
        public String process(ToolExecutionException exception) {
            LAST.set(exception);
            return delegate.process(exception);
        }
    }
}
