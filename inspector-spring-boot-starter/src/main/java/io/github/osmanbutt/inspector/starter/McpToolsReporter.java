package io.github.osmanbutt.inspector.starter;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.mcp.AsyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Tells the inspector which of the app's tools come from MCP servers, so it can label them.
 * After each tool call it reads the MCP tool callbacks on the reporting thread and posts them.
 * Spring AI's MCP tool callback providers cache their callbacks, and the ChatClient filled
 * that cache to offer the tools before any of them could run, so this makes no MCP call.
 *
 * <p>Only registered when Spring AI's MCP client is on the classpath.
 */
class McpToolsReporter implements ObservationHandler<ToolCallingObservationContext> {

    record McpTool(String name, String server) {
    }

    record McpToolsReport(String app, List<McpTool> tools) {
    }

    private static final Log log = LogFactory.getLog(McpToolsReporter.class);

    private final InspectorClient client;
    private final String app;
    private final Supplier<Stream<ToolCallbackProvider>> providers;
    /** So the inspector hears once when the last MCP tool went away. */
    private volatile boolean hadTools;

    McpToolsReporter(InspectorClient client, String app, Supplier<Stream<ToolCallbackProvider>> providers) {
        this.client = client;
        this.app = app;
        this.providers = providers;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        if (!ToolExecutionObservationHandler.isRepeat(context)) {
            client.postLater("/api/mcp-tools", this::report);
        }
    }

    /**
     * Null when the app has no MCP tools and had none before: there is nothing to label. Runs
     * on the reporting thread only.
     */
    McpToolsReport report() {
        List<McpTool> tools = new ArrayList<>();
        providers.get().forEach(provider -> {
            try {
                for (ToolCallback callback : provider.getToolCallbacks()) {
                    if (callback instanceof SyncMcpToolCallback || callback instanceof AsyncMcpToolCallback) {
                        tools.add(new McpTool(callback.getToolDefinition().name(), server(callback)));
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Could not list the tools of " + provider, e);
            }
        });
        tools.sort(Comparator.comparing(McpTool::name));
        boolean had = hadTools;
        hadTools = !tools.isEmpty();
        return tools.isEmpty() && !had ? null : new McpToolsReport(app, tools);
    }

    /**
     * The name the MCP server gave itself. Spring AI keeps the callback's client private, so it
     * is read reflectively; {@code ""} when that fails, and the tool is still labelled MCP.
     */
    static String server(ToolCallback callback) {
        try {
            Object mcpClient = clientField(callback.getClass()).get(callback);
            McpSchema.Implementation info = mcpClient instanceof McpSyncClient sync ? sync.getServerInfo()
                    : mcpClient instanceof McpAsyncClient async ? async.getServerInfo() : null;
            return info == null || info.name() == null ? "" : info.name();
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("Could not tell which MCP server " + callback.getToolDefinition().name() + " comes from", e);
            return "";
        }
    }

    private static Field clientField(Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("mcpClient");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException("mcpClient in " + type.getName());
    }
}
