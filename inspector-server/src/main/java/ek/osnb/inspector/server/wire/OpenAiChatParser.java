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
 * OpenAI Chat Completions ({@code POST .../chat/completions}), also spoken by Docker Model
 * Runner, Ollama's {@code /v1} endpoint and many other servers.
 */
@Component
public class OpenAiChatParser implements WireParser {

    @Override
    public boolean supports(Exchange exchange) {
        return exchange.request().path().endsWith("/chat/completions");
    }

    @Override
    public ParsedExchange parse(Exchange exchange) {
        JsonNode request = Json.parse(exchange.request().body());
        boolean streamed = Json.bool(request.path("stream"));

        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode message : request.path("messages")) {
            messages.add(message(message));
        }
        List<ToolSpec> tools = new ArrayList<>();
        for (JsonNode tool : request.path("tools")) {
            JsonNode function = tool.path("function");
            tools.add(new ToolSpec(text(function.path("name")), text(function.path("description")),
                    function.path("parameters")));
        }

        String model = text(request.path("model"));
        Exchange.Response response = exchange.response();
        if (response == null) {
            return new ParsedExchange("openai", model, streamed, messages, tools, null, null, null, null);
        }
        // A failed streaming call answers with a plain JSON error, not a stream.
        Answer answer = streamed && response.status() < 400
                ? fromStream(response.body())
                : fromJson(Json.parse(response.body()));
        return new ParsedExchange("openai", model != null ? model : answer.model(), streamed, messages, tools,
                answer.output(), answer.finishReason(), answer.usage(), WireParser.errorMessage(response, answer.error()));
    }

    private record Answer(ChatMessage output, String finishReason, Usage usage, String model, String error) {
    }

    private static Answer fromJson(JsonNode body) {
        JsonNode choice = body.path("choices").path(0);
        return new Answer(
                choice.isMissingNode() ? null : message(choice.path("message")),
                text(choice.path("finish_reason")),
                usage(body.path("usage")),
                text(body.path("model")),
                text(body.path("error").path("message")));
    }

    /**
     * Rebuilds the answer from {@code chat.completion.chunk} events: text and reasoning
     * arrive as fragments, and each tool call's arguments are split over many chunks
     * (matched by {@code index}). Usage comes in a last chunk without choices.
     */
    private static Answer fromStream(String body) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, StreamedCall> calls = new TreeMap<>();
        String finishReason = null;
        Usage usage = null;
        String model = null;
        String error = null;
        for (SseFrames.Frame frame : SseFrames.parse(body)) {
            if (frame.data().equals("[DONE]")) {
                continue;
            }
            JsonNode chunk = Json.parse(frame.data());
            model = model != null ? model : text(chunk.path("model"));
            error = error != null ? error : text(chunk.path("error").path("message"));
            if (chunk.hasNonNull("usage")) {
                usage = usage(chunk.path("usage"));
            }
            JsonNode choice = chunk.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            appendText(text, delta.path("content"));
            appendText(reasoning, delta.path("reasoning_content"));
            int position = 0;
            for (JsonNode call : delta.path("tool_calls")) {
                Integer index = integer(call.path("index"));
                StreamedCall built = calls.computeIfAbsent(index != null ? index : position, i -> new StreamedCall());
                built.id = built.id != null ? built.id : text(call.path("id"));
                built.name = built.name != null ? built.name : text(call.path("function").path("name"));
                appendText(built.arguments, call.path("function").path("arguments"));
                position++;
            }
            finishReason = text(choice.path("finish_reason")) != null ? text(choice.path("finish_reason")) : finishReason;
        }

        List<Part> parts = new ArrayList<>();
        if (!reasoning.isEmpty()) {
            parts.add(new Part.Thinking(reasoning.toString()));
        }
        if (!text.isEmpty()) {
            parts.add(new Part.Text(text.toString()));
        }
        calls.values().forEach(call -> parts.add(new Part.ToolCall(call.id, call.name, call.arguments.toString())));
        return new Answer(new ChatMessage("assistant", parts), finishReason, usage, model, error);
    }

    private static final class StreamedCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private static void appendText(StringBuilder target, JsonNode fragment) {
        if (fragment.isString()) {
            target.append(fragment.stringValue());
        }
    }

    static ChatMessage message(JsonNode message) {
        String role = text(message.path("role"));
        List<Part> parts = new ArrayList<>();
        // Reasoning models served through OpenAI-compatible APIs (DeepSeek, Qwen, ...) use this field.
        String reasoning = text(message.path("reasoning_content"));
        if (reasoning != null && !reasoning.isBlank()) {
            parts.add(new Part.Thinking(reasoning));
        }
        if ("tool".equals(role)) {
            parts.add(new Part.ToolResult(text(message.path("tool_call_id")), content(message.path("content")), false));
        } else {
            parts.addAll(contentParts(message.path("content")));
        }
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            parts.add(new Part.ToolCall(text(call.path("id")), text(function.path("name")),
                    textOrJson(function.path("arguments"))));
        }
        return new ChatMessage(role, parts);
    }

    /** Content is either a string or an array of typed parts. */
    private static List<Part> contentParts(JsonNode content) {
        List<Part> parts = new ArrayList<>();
        if (content.isString()) {
            if (!content.stringValue().isEmpty()) {
                parts.add(new Part.Text(content.stringValue()));
            }
            return parts;
        }
        for (JsonNode part : content) {
            String type = text(part.path("type"));
            if ("text".equals(type)) {
                parts.add(new Part.Text(text(part.path("text"))));
            } else if ("image_url".equals(type)) {
                parts.add(new Part.Other("image"));
            } else {
                parts.add(new Part.Other(type));
            }
        }
        return parts;
    }

    /** A tool message's content as one string, joining text parts if it was sent as an array. */
    private static String content(JsonNode content) {
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            content.forEach(part -> text.append(text(part.path("text")) == null ? "" : text(part.path("text"))));
            return text.toString();
        }
        return textOrJson(content);
    }

    static Usage usage(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        return new Usage(integer(usage.path("prompt_tokens")), integer(usage.path("completion_tokens")),
                integer(usage.path("prompt_tokens_details").path("cached_tokens")), null);
    }
}
