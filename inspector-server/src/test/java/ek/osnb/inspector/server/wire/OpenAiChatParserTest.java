package ek.osnb.inspector.server.wire;

import ek.osnb.inspector.server.InspectorProperties;
import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.wire.ParsedExchange.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ek.osnb.inspector.server.wire.Fixtures.exchange;
import static ek.osnb.inspector.server.wire.Fixtures.pending;
import static ek.osnb.inspector.server.wire.Fixtures.read;
import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures are real Docker Model Runner traffic recorded with the sample app. */
class OpenAiChatParserTest {

    final OpenAiChatParser parser = new OpenAiChatParser();

    @Test
    void parsesARoundTripThatAsksForTools() {
        ParsedExchange parsed = parser.parse(exchange("/chat/completions",
                read("openai/tool-call.request.json"), 200, read("openai/tool-call.response.json")));

        assertThat(parsed.provider()).isEqualTo("openai");
        assertThat(parsed.model()).isEqualTo("ai/qwen3:4b-instruct-2507-q4_K_M");
        assertThat(parsed.streamed()).isFalse();
        assertThat(parsed.messages()).extracting(ChatMessage::role).containsExactly("system", "user");
        assertThat(parsed.messages().get(1).parts())
                .containsExactly(new Part.Text("What is the weather in Copenhagen and what time is it there?"));

        assertThat(parsed.tools()).extracting(ParsedExchange.ToolSpec::name).containsExactly("currentTime", "weather");
        assertThat(parsed.tools().getFirst().description()).isEqualTo("Current date and time in a time zone");
        assertThat(parsed.tools().getFirst().schema().path("properties").has("zone")).isTrue();

        assertThat(parsed.output().role()).isEqualTo("assistant");
        assertThat(parsed.output().parts()).containsExactly(
                new Part.ToolCall("dkkzu6IBP78L993owMdFsb55uQkLTmyg", "weather", "{\"city\": \"Copenhagen\"}"),
                new Part.ToolCall("giiqHefFwpjJ8nG4xulRxj0Nf8mfkWxi", "currentTime", "{\"zone\": \"Europe/Copenhagen\"}"));
        assertThat(parsed.finishReason()).isEqualTo("tool_calls");
        assertThat(parsed.usage()).isEqualTo(new Usage(310, 41, 0, null));
        assertThat(parsed.error()).isNull();
    }

    @Test
    void parsesARoundTripThatSendsToolResultsBack() {
        ParsedExchange parsed = parser.parse(exchange("/chat/completions",
                read("openai/tool-result.request.json"), 200, read("openai/tool-result.response.json")));

        assertThat(parsed.messages()).extracting(ChatMessage::role)
                .containsExactly("system", "user", "assistant", "tool", "tool");
        assertThat(parsed.messages().get(2).parts()).hasSize(2).allMatch(Part.ToolCall.class::isInstance);
        assertThat(parsed.messages().get(3).parts()).containsExactly(new Part.ToolResult(
                "dkkzu6IBP78L993owMdFsb55uQkLTmyg",
                "{\"city\":\"Copenhagen\",\"temperatureCelsius\":1,\"conditions\":\"windy\"}", false));

        assertThat(parsed.output().parts()).singleElement()
                .isInstanceOfSatisfying(Part.Text.class, text -> assertThat(text.text()).startsWith("In Copenhagen"));
        assertThat(parsed.finishReason()).isEqualTo("stop");
        assertThat(parsed.usage()).isEqualTo(new Usage(418, 34, 350, null));
    }

    @Test
    void reassemblesAStreamedToolCall() {
        ParsedExchange parsed = parser.parse(exchange("/chat/completions",
                read("openai/stream-tool-call.request.json"), 200, read("openai/stream-tool-call.response.sse")));

        assertThat(parsed.streamed()).isTrue();
        assertThat(parsed.messages()).extracting(ChatMessage::role).containsExactly("system", "user");
        assertThat(parsed.output().parts()).containsExactly(
                new Part.ToolCall("N62RNiGELvb7sJh3NuOCDAKi9tvHv81f", "weather", "{\"city\": \"Oslo\"}"));
        assertThat(parsed.finishReason()).isEqualTo("tool_calls");
        assertThat(parsed.usage()).isEqualTo(new Usage(304, 20, 292, null));
    }

    @Test
    void reassemblesStreamedText() {
        ParsedExchange parsed = parser.parse(exchange("/chat/completions",
                read("openai/stream-tool-call.request.json"), 200, read("openai/stream-answer.response.sse")));

        assertThat(parsed.output().parts())
                .containsExactly(new Part.Text("The weather in Oslo is sunny with a temperature of -2°C."));
        assertThat(parsed.finishReason()).isEqualTo("stop");
        assertThat(parsed.usage()).isEqualTo(new Usage(353, 15, 323, null));
    }

    @Test
    void joinsToolCallArgumentsSplitOverChunksPerIndex() {
        String stream = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"weather","arguments":"{\\"ci"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"currentTime","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\\": \\"Oslo\\"}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        ParsedExchange parsed = parser.parse(exchange("/chat/completions", "{\"stream\": true}", 200, stream));

        assertThat(parsed.output().parts()).containsExactly(
                new Part.ToolCall("a", "weather", "{\"city\": \"Oslo\"}"),
                new Part.ToolCall("b", "currentTime", "{}"));
    }

    @Test
    void failedStreamingCallReadsTheJsonError() {
        ParsedExchange parsed = parser.parse(exchange("/chat/completions", "{\"stream\": true}", 429,
                "{\"error\":{\"message\":\"Rate limit reached\"}}"));

        assertThat(parsed.error()).isEqualTo("Rate limit reached");
    }

    @Test
    void pendingExchangeHasMessagesButNoOutput() {
        ParsedExchange parsed = parser.parse(pending("/chat/completions", read("openai/tool-call.request.json")));

        assertThat(parsed.messages()).hasSize(2);
        assertThat(parsed.output()).isNull();
        assertThat(parsed.usage()).isNull();
    }

    @Test
    void reportsTheProviderErrorMessage() {
        ParsedExchange parsed = parser.parse(exchange("/chat/completions", read("openai/tool-call.request.json"), 404,
                "{\"error\":{\"message\":\"model not found\",\"type\":\"invalid_request_error\"}}"));

        assertThat(parsed.output()).isNull();
        assertThat(parsed.error()).isEqualTo("model not found");
    }

    @Test
    void readsContentSentAsAnArrayOfParts() {
        ParsedExchange parsed = parser.parse(pending("/chat/completions", """
                {"model": "gpt-5-mini", "messages": [{"role": "user", "content": [
                  {"type": "text", "text": "What is in this picture?"},
                  {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}]}]}
                """));

        assertThat(parsed.messages().getFirst().parts())
                .containsExactly(new Part.Text("What is in this picture?"), new Part.Other("image"));
    }

    @Test
    void supportsChatCompletionsOnly() {
        assertThat(parser.supports(pending("/chat/completions", "{}"))).isTrue();
        assertThat(parser.supports(pending("/v1/chat/completions", "{}"))).isTrue();
        assertThat(parser.supports(pending("/embeddings", "{}"))).isFalse();
    }

    @Test
    void wireParsersNeverFailOnGarbage() {
        Exchange garbage = exchange("/chat/completions", "not json", 200, "<html>oops</html>");
        WireParsers parsers = new WireParsers(List.of(new WireParser() {
            @Override
            public boolean supports(Exchange exchange) {
                return true;
            }

            @Override
            public ParsedExchange parse(Exchange exchange) {
                throw new IllegalStateException("boom");
            }
        }), new InspectorProperties(null, 10, null));

        assertThat(parsers.parse(garbage)).isEmpty();
        assertThat(parser.parse(garbage).messages()).isEmpty(); // lenient: missing JSON is just empty
    }
}
