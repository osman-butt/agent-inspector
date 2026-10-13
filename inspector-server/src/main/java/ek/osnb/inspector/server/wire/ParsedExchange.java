package ek.osnb.inspector.server.wire;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.stream.Stream;

/**
 * A model round-trip in a provider-neutral shape: what the app sent (messages, offered
 * tools) and what the model answered. System prompts are messages with role {@code system}.
 *
 * @param output       the model's answer, {@code null} while pending or when the call failed
 * @param finishReason the provider's own value, e.g. {@code tool_calls} or {@code end_turn}
 * @param error        the provider's error message when the call failed
 */
public record ParsedExchange(
        String provider,
        String model,
        boolean streamed,
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        ChatMessage output,
        String finishReason,
        Usage usage,
        String error) {

    /** Ids of every tool call in this round-trip: the ones re-sent in the request and the ones in the answer. */
    @JsonIgnore
    public List<String> toolCallIds() {
        return Stream.concat(messages.stream(), Stream.ofNullable(output))
                .flatMap(message -> message.parts().stream())
                .filter(Part.ToolCall.class::isInstance)
                .map(part -> ((Part.ToolCall) part).id())
                .toList();
    }

    public record ToolSpec(String name, String description, JsonNode schema) {
    }

    /** Token counts as reported by the provider; any of them may be missing. */
    public record Usage(Integer inputTokens, Integer outputTokens, Integer cacheReadTokens, Integer cacheWriteTokens) {
    }
}
