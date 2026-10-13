package ek.osnb.inspector.server.wire;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.wire.ParsedExchange.ToolSpec;
import ek.osnb.inspector.server.wire.ParsedExchange.Usage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static ek.osnb.inspector.server.wire.Json.integer;
import static ek.osnb.inspector.server.wire.Json.text;
import static ek.osnb.inspector.server.wire.Json.textOrJson;

/**
 * Anthropic Messages API ({@code POST /v1/messages}). The system prompt is a top-level field
 * rather than a message, content is a list of typed blocks, and tool results travel back
 * inside a user message.
 */
@Component
public class AnthropicMessagesParser implements WireParser {

    @Override
    public boolean supports(Exchange exchange) {
        return exchange.request().path().endsWith("/v1/messages");
    }

    @Override
    public ParsedExchange parse(Exchange exchange) {
        JsonNode request = Json.parse(exchange.request().body());
        boolean streamed = Json.bool(request.path("stream"));

        List<ChatMessage> messages = new ArrayList<>();
        JsonNode system = request.path("system");
        if (!system.isMissingNode() && !system.isNull()) {
            messages.add(new ChatMessage("system", blocks(system)));
        }
        for (JsonNode message : request.path("messages")) {
            messages.add(new ChatMessage(text(message.path("role")), blocks(message.path("content"))));
        }
        List<ToolSpec> tools = new ArrayList<>();
        for (JsonNode tool : request.path("tools")) {
            // Server tools (web search, code execution, ...) have a type and no input schema.
            tools.add(new ToolSpec(text(tool.path("name")), text(tool.path("description")),
                    tool.has("input_schema") ? tool.path("input_schema") : null));
        }

        String model = text(request.path("model"));
        Exchange.Response response = exchange.response();
        if (response == null) {
            return new ParsedExchange("anthropic", model, streamed, messages, tools, null, null, null, null);
        }
        Answer answer = streamed && response.status() < 400
                ? fromStream(response.body())
                : fromJson(Json.parse(response.body()));
        return new ParsedExchange("anthropic", model != null ? model : answer.model(), streamed, messages, tools,
                answer.output(), answer.stopReason(), answer.usage(), WireParser.errorMessage(response, answer.error()));
    }

    private record Answer(ChatMessage output, String stopReason, Usage usage, String model, String error) {
    }

    private static Answer fromJson(JsonNode body) {
        JsonNode content = body.path("content");
        return new Answer(
                content.isArray() ? new ChatMessage("assistant", blocks(content)) : null,
                text(body.path("stop_reason")),
                usage(body.path("usage"), null),
                text(body.path("model")),
                text(body.path("error").path("message")));
    }

    /**
     * Rebuilds the message from stream events: {@code content_block_start} opens a block at an
     * index, {@code content_block_delta} adds text, thinking or a fragment of a tool call's
     * input JSON, and {@code message_delta} carries the stop reason and final output tokens.
     */
    private static Answer fromStream(String body) {
        Map<Integer, StreamedBlock> blocks = new TreeMap<>();
        String stopReason = null;
        Usage usage = null;
        String model = null;
        String error = null;
        for (SseFrames.Frame frame : SseFrames.parse(body)) {
            JsonNode event = Json.parse(frame.data());
            Integer index = integer(event.path("index"));
            switch (text(event.path("type"))) {
                case "message_start" -> {
                    model = text(event.path("message").path("model"));
                    usage = usage(event.path("message").path("usage"), null);
                }
                case "content_block_start" -> blocks.put(index, new StreamedBlock(event.path("content_block")));
                case "content_block_delta" -> {
                    StreamedBlock block = blocks.get(index);
                    if (block != null) {
                        block.append(event.path("delta"));
                    }
                }
                case "message_delta" -> {
                    stopReason = text(event.path("delta").path("stop_reason"));
                    usage = usage(event.path("usage"), usage);
                }
                case "error" -> error = text(event.path("error").path("message"));
                case null, default -> {
                    // message_stop, content_block_stop, ping
                }
            }
        }
        List<Part> parts = blocks.values().stream().map(StreamedBlock::toPart).toList();
        return new Answer(new ChatMessage("assistant", parts), stopReason, usage, model, error);
    }

    private static final class StreamedBlock {
        private final JsonNode start;
        private final StringBuilder content = new StringBuilder();

        StreamedBlock(JsonNode start) {
            this.start = start;
        }

        void append(JsonNode delta) {
            String fragment = switch (text(delta.path("type"))) {
                case "text_delta" -> text(delta.path("text"));
                case "thinking_delta" -> text(delta.path("thinking"));
                case "input_json_delta" -> text(delta.path("partial_json"));
                case null, default -> null; // signature_delta, citations_delta
            };
            if (fragment != null) {
                content.append(fragment);
            }
        }

        Part toPart() {
            return switch (text(start.path("type"))) {
                case "text" -> new Part.Text(content.toString());
                case "thinking" -> new Part.Thinking(content.toString());
                // The input arrives as JSON fragments; a tool without arguments sends none.
                case "tool_use" -> new Part.ToolCall(text(start.path("id")), text(start.path("name")),
                        content.isEmpty() ? textOrJson(start.path("input")) : content.toString());
                case null, default -> block(start);
            };
        }
    }

    /** Content is either a plain string or a list of typed blocks. */
    private static List<Part> blocks(JsonNode content) {
        List<Part> parts = new ArrayList<>();
        if (content.isString()) {
            parts.add(new Part.Text(content.stringValue()));
            return parts;
        }
        for (JsonNode block : content) {
            parts.add(block(block));
        }
        return parts;
    }

    private static Part block(JsonNode block) {
        String type = text(block.path("type"));
        return switch (type) {
            case "text" -> new Part.Text(text(block.path("text")));
            case "thinking" -> new Part.Thinking(text(block.path("thinking")));
            case "tool_use" -> new Part.ToolCall(text(block.path("id")), text(block.path("name")),
                    textOrJson(block.path("input")));
            case "tool_result" -> new Part.ToolResult(text(block.path("tool_use_id")),
                    toolResultContent(block.path("content")), Json.bool(block.path("is_error")));
            case "redacted_thinking" -> new Part.Other("redacted thinking");
            case null -> new Part.Other("unknown");
            default -> new Part.Other(type.replace('_', ' '));
        };
    }

    /** A tool result's content is a string or a list of blocks; the text blocks are joined. */
    private static String toolResultContent(JsonNode content) {
        if (!content.isArray()) {
            return textOrJson(content);
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode block : content) {
            texts.add("text".equals(text(block.path("type"))) ? text(block.path("text")) : "[" + text(block.path("type")) + "]");
        }
        return String.join("\n", texts);
    }

    /** Reads usage, keeping values from {@code previous} that this event doesn't repeat. */
    private static Usage usage(JsonNode usage, Usage previous) {
        if (usage.isMissingNode() || usage.isNull()) {
            return previous;
        }
        return new Usage(
                orElse(integer(usage.path("input_tokens")), previous == null ? null : previous.inputTokens()),
                orElse(integer(usage.path("output_tokens")), previous == null ? null : previous.outputTokens()),
                orElse(integer(usage.path("cache_read_input_tokens")), previous == null ? null : previous.cacheReadTokens()),
                orElse(integer(usage.path("cache_creation_input_tokens")), previous == null ? null : previous.cacheWriteTokens()));
    }

    private static Integer orElse(Integer value, Integer fallback) {
        return value != null ? value : fallback;
    }
}
