package io.github.osmanbutt.inspector.server.api;

import io.github.osmanbutt.inspector.server.capture.Exchange;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore;
import io.github.osmanbutt.inspector.server.proxy.RouteRegistry;
import io.github.osmanbutt.inspector.server.tools.McpToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class ExchangeControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ExchangeStore store;

    @Autowired
    RouteRegistry routes;

    @Autowired
    McpToolCatalog mcpTools;

    Exchange finished;
    Exchange pending;

    @BeforeEach
    void seed() {
        store.clear();
        finished = Exchange.start("dmr", request("/chat/completions"))
                .complete(new Exchange.Response(200, Map.of(), "{\"id\":\"chatcmpl-1\"}", 420, null));
        pending = Exchange.start("anthropic", request("/v1/messages"));
        store.save(finished);
        store.save(pending);
    }

    @Test
    void listsSummariesNewestFirst() {
        assertThat(mvc.get().uri("/api/exchanges"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        [
                          {"id": "%s", "route": "anthropic", "path": "/v1/messages", "status": null},
                          {"id": "%s", "route": "dmr", "method": "POST", "path": "/chat/completions",
                           "status": 200, "durationMs": 420, "provider": "openai", "model": "ai/qwen3",
                           "requestBytes": 20, "responseBytes": 19}
                        ]
                        """.formatted(pending.id(), finished.id()));
        assertThat(mvc.get().uri("/api/exchanges"))
                .bodyJson()
                .extractingPath("$[0].id").isEqualTo(pending.id());
    }

    @Test
    void returnsTheFullExchange() {
        assertThat(mvc.get().uri("/api/exchanges/{id}", finished.id()))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"request": {"body": "{\\"model\\":\\"ai/qwen3\\"}"},
                         "response": {"body": "{\\"id\\":\\"chatcmpl-1\\"}"},
                         "parsed": {"provider": "openai", "model": "ai/qwen3"}}
                        """);
    }

    @Test
    void labelsTheToolsOfItsAppThatComeFromMcpServers() {
        routes.register("mcp-exchange-openai", URI.create("http://localhost:12434/engines/v1"), "mcp-exchange-app");
        mcpTools.save("mcp-exchange-app", Map.of("weather", "weather-server"));
        Exchange exchange = Exchange.start("mcp-exchange-openai", request("/chat/completions"));
        store.save(exchange);

        assertThat(mvc.get().uri("/api/exchanges/{id}", exchange.id()))
                .bodyJson()
                .extractingPath("$.mcpTools").isEqualTo(Map.of("weather", "weather-server"));
        assertThat(mvc.get().uri("/api/exchanges/{id}", finished.id()))
                .bodyJson()
                .extractingPath("$.mcpTools").asMap().isEmpty();
    }

    @Test
    void partsCarryTheirType() {
        Exchange toolCall = Exchange.start("dmr", request("/chat/completions")).complete(new Exchange.Response(200, Map.of(), """
                {"choices": [{"finish_reason": "tool_calls", "message": {"role": "assistant", "tool_calls": [
                  {"id": "call_1", "type": "function", "function": {"name": "weather", "arguments": "{}"}}]}}]}
                """, 300, null));
        store.save(toolCall);

        assertThat(mvc.get().uri("/api/exchanges/{id}", toolCall.id()))
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"parsed": {"output": {"role": "assistant", "parts": [
                          {"type": "tool_call", "id": "call_1", "name": "weather", "arguments": "{}"}]}}}
                        """);
        // the message's wire JSON comes along as JSON, not as a string
        assertThat(mvc.get().uri("/api/exchanges/{id}", toolCall.id()))
                .bodyJson()
                .extractingPath("$.parsed.output.raw.tool_calls[0].function.name").isEqualTo("weather");
    }

    @Test
    void countsBodySizesInUtf8() {
        assertThat(ExchangeSummary.utf8Length("aé€😀")).isEqualTo(1 + 2 + 3 + 4);
        assertThat(ExchangeSummary.utf8Length(null)).isZero();
    }

    @Test
    void unknownIdIsNotFound() {
        assertThat(mvc.get().uri("/api/exchanges/nope")).hasStatus(404);
    }

    @Test
    void clearRemovesEverything() {
        assertThat(mvc.delete().uri("/api/exchanges")).hasStatus(204);
        assertThat(store.findAll()).isEmpty();
    }

    private static Exchange.Request request(String path) {
        return new Exchange.Request("POST", "http://upstream" + path, path, Map.of(), "{\"model\":\"ai/qwen3\"}");
    }
}
