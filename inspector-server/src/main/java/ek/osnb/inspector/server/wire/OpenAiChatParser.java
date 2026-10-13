package ek.osnb.inspector.server.wire;

import ek.osnb.inspector.server.capture.Exchange;
import ek.osnb.inspector.server.wire.ParsedExchange.ToolSpec;
import ek.osnb.inspector.server.wire.ParsedExchange.Usage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

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

        ChatMessage output = null;
        String finishReason = null;
        Usage usage = null;
        String error = null;
        String model = text(request.path("model"));
        Exchange.Response response = exchange.response();
        if (response != null && !streamed) {
            JsonNode body = Json.parse(response.body());
            JsonNode choice = body.path("choices").path(0);
            if (!choice.isMissingNode()) {
                output = message(choice.path("message"));
                finishReason = text(choice.path("finish_reason"));
            }
            usage = usage(body.path("usage"));
            error = errorMessage(response, body);
            model = model != null ? model : text(body.path("model"));
        } else if (response != null) {
            error = response.error();
        }
        return new ParsedExchange("openai", model, streamed, messages, tools, output, finishReason, usage, error);
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

    private static String errorMessage(Exchange.Response response, JsonNode body) {
        if (response.error() != null) {
            return response.error();
        }
        if (response.status() < 400) {
            return null;
        }
        String message = text(body.path("error").path("message"));
        return message != null ? message : "HTTP " + response.status();
    }
}
