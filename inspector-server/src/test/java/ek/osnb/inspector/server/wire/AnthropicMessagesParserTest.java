package ek.osnb.inspector.server.wire;

import ek.osnb.inspector.server.wire.ParsedExchange.Usage;
import org.junit.jupiter.api.Test;

import static ek.osnb.inspector.server.wire.Fixtures.exchange;
import static ek.osnb.inspector.server.wire.Fixtures.pending;
import static ek.osnb.inspector.server.wire.Fixtures.read;
import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures follow the documented Messages API format. */
class AnthropicMessagesParserTest {

    final AnthropicMessagesParser parser = new AnthropicMessagesParser();

    @Test
    void parsesARoundTripThatAsksForATool() {
        ParsedExchange parsed = parser.parse(exchange("/v1/messages",
                read("anthropic/tool-use.request.json"), 200, read("anthropic/tool-use.response.json")));

        assertThat(parsed.provider()).isEqualTo("anthropic");
        assertThat(parsed.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(parsed.streamed()).isFalse();
        assertThat(parsed.messages()).extracting(ChatMessage::role).containsExactly("system", "user");
        assertThat(parsed.messages().getFirst().parts()).containsExactly(
                new Part.Text("You are a helpful assistant. Use the tools when they help. Answer briefly."));
        assertThat(parsed.messages().get(1).parts()).containsExactly(new Part.Text("What is the weather in Copenhagen?"));
        assertThat(parsed.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("weather");
            assertThat(tool.schema().path("required").path(0).stringValue()).isEqualTo("city");
        });

        assertThat(parsed.output().parts()).containsExactly(
                new Part.Text("I'll check the weather in Copenhagen."),
                new Part.ToolCall("toolu_01A09q90qw90lq917835lq9", "weather", "{\"city\":\"Copenhagen\"}"));
        assertThat(parsed.finishReason()).isEqualTo("tool_use");
        assertThat(parsed.usage()).isEqualTo(new Usage(412, 58, 1024, 0));
    }

    @Test
    void parsesToolResultsThinkingAndOtherBlocks() {
        ParsedExchange parsed = parser.parse(pending("/v1/messages", read("anthropic/tool-result.request.json")));

        assertThat(parsed.messages()).extracting(ChatMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(parsed.messages().get(1).parts()).containsExactly(
                new Part.Text("What is the weather in Copenhagen and Atlantis? Here is a map."),
                new Part.Other("image"));
        assertThat(parsed.messages().get(2).parts()).containsExactly(
                new Part.Thinking("Two cities, so two weather calls."),
                new Part.ToolCall("toolu_01A", "weather", "{\"city\":\"Copenhagen\"}"),
                new Part.ToolCall("toolu_01B", "weather", "{\"city\":\"Atlantis\"}"));
        assertThat(parsed.messages().get(3).parts()).containsExactly(
                new Part.ToolResult("toolu_01A", "{\"city\":\"Copenhagen\",\"temperatureCelsius\":1,\"conditions\":\"windy\"}", false),
                new Part.ToolResult("toolu_01B", "Unknown city: Atlantis", true));
        assertThat(parsed.tools()).extracting(ParsedExchange.ToolSpec::name).containsExactly("weather", "web_search");
        assertThat(parsed.tools().get(1).schema()).isNull();
        assertThat(parsed.output()).isNull();
    }

    @Test
    void reassemblesAStreamedAnswer() {
        String request = read("anthropic/tool-use.request.json").replace("\"max_tokens\": 1024,", "\"max_tokens\": 1024, \"stream\": true,");
        ParsedExchange parsed = parser.parse(exchange("/v1/messages", request, 200, read("anthropic/tool-use.response.sse")));

        assertThat(parsed.streamed()).isTrue();
        assertThat(parsed.output().parts()).containsExactly(
                new Part.Thinking("The user wants the weather in Copenhagen."),
                new Part.Text("Let me check that for you."),
                new Part.ToolCall("toolu_01T1x1fJ34qAmk2tNTrN7Up6", "weather", "{\"city\": \"Copenhagen\"}"));
        assertThat(parsed.finishReason()).isEqualTo("tool_use");
        // input and cache tokens come with message_start, the final output tokens with message_delta
        assertThat(parsed.usage()).isEqualTo(new Usage(412, 89, 0, 1024));
    }

    @Test
    void reportsTheProviderErrorMessage() {
        ParsedExchange parsed = parser.parse(exchange("/v1/messages", read("anthropic/tool-use.request.json"), 529,
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));

        assertThat(parsed.output()).isNull();
        assertThat(parsed.error()).isEqualTo("Overloaded");
    }

    @Test
    void supportsTheMessagesEndpointOnly() {
        assertThat(parser.supports(pending("/v1/messages", "{}"))).isTrue();
        assertThat(parser.supports(pending("/v1/messages/count_tokens", "{}"))).isFalse();
        assertThat(parser.supports(pending("/chat/completions", "{}"))).isFalse();
    }
}
