package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.capture.ExchangeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class ExchangeControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ExchangeStore store;

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
                           "status": 200, "durationMs": 420}
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
