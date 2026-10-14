package io.github.osmanbutt.inspector.starter;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolsReporterTest {

    final List<ToolCallbackProvider> providers = new CopyOnWriteArrayList<>();

    FakeInspector inspector;
    InspectorClient client;
    ObservationRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        inspector = FakeInspector.inspector();
        client = new InspectorClient(inspector.url());
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new McpToolsReporter(client, "sample", providers::stream));
    }

    @AfterEach
    void tearDown() {
        client.close();
        inspector.close();
    }

    @Test
    void reportsWhichToolsComeFromWhichMcpServerAfterEachToolCall() throws Exception {
        providers.add(ToolCallbackProvider.from(mcpTool("forecast", "weather-server"), localTool("currentTime")));

        toolCall("call_1");
        toolCall("call_2");

        assertThat(awaitPosts(2)).containsExactly(
                "/api/mcp-tools {\"app\":\"sample\",\"tools\":[{\"name\":\"forecast\",\"server\":\"weather-server\"}]}",
                "/api/mcp-tools {\"app\":\"sample\",\"tools\":[{\"name\":\"forecast\",\"server\":\"weather-server\"}]}");
    }

    @Test
    void reportsNothingForAnAppWithoutMcpTools() throws Exception {
        providers.add(ToolCallbackProvider.from(localTool("currentTime")));

        toolCall("call_1");

        Thread.sleep(300);
        assertThat(inspector.posted).isEmpty();
    }

    @Test
    void saysOnceWhenTheLastMcpToolIsGone() throws Exception {
        providers.add(ToolCallbackProvider.from(mcpTool("forecast", "weather-server")));
        toolCall("call_1");
        awaitPosts(1);

        providers.clear();
        toolCall("call_2");
        toolCall("call_3");

        Thread.sleep(300);
        assertThat(inspector.posted).hasSize(2).last().isEqualTo("/api/mcp-tools {\"app\":\"sample\",\"tools\":[]}");
    }

    @Test
    void aServerThatHasNotIntroducedItselfIsStillMcp() throws Exception {
        providers.add(ToolCallbackProvider.from(mcpTool("forecast", null)));

        toolCall("call_1");

        assertThat(awaitPosts(1)).singleElement().asString().contains("{\"name\":\"forecast\",\"server\":\"\"}");
    }

    @Test
    void aProviderThatFailsCostsTheAppNothing() throws Exception {
        providers.add(() -> {
            throw new IllegalStateException("MCP server down");
        });
        providers.add(ToolCallbackProvider.from(mcpTool("forecast", "weather-server")));

        toolCall("call_1"); // must not throw

        assertThat(awaitPosts(1)).singleElement().asString().contains("\"name\":\"forecast\"");
    }

    private static ToolCallback mcpTool(String name, String server) {
        McpSyncClient mcpClient = mock(McpSyncClient.class);
        when(mcpClient.getClientInfo()).thenReturn(McpSchema.Implementation.builder("spring-ai-mcp-client - weather", "1.0.0").build());
        when(mcpClient.getServerInfo()).thenReturn(server == null ? null : McpSchema.Implementation.builder(server, "1.0.0").build());
        McpSchema.Tool tool = McpSchema.Tool.builder(name, Map.of("type", "object")).description(name).build();
        return SyncMcpToolCallback.builder().mcpClient(mcpClient).tool(tool).prefixedToolName(name).build();
    }

    private static ToolCallback localTool(String name) {
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "14:05";
            }
        };
    }

    private void toolCall(String toolCallId) {
        ToolCallingObservationContext context = ToolCallingObservationContext.builder()
                .toolDefinition(ToolDefinition.builder().name("forecast").description("Forecast").inputSchema("{}").build())
                .toolCallId(toolCallId)
                .toolCallArguments("{}")
                .build();
        Observation.createNotStarted("spring.ai.tool", () -> context, registry).observe(() -> {
        });
    }

    private List<String> awaitPosts(int count) throws InterruptedException {
        for (int i = 0; i < 40 && inspector.posted.size() < count; i++) {
            Thread.sleep(50);
        }
        return List.copyOf(inspector.posted);
    }
}
