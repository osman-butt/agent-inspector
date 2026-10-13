package ek.osnb.inspector.server.wire;

import tools.jackson.databind.JsonNode;

import java.util.List;

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

    public record ToolSpec(String name, String description, JsonNode schema) {
    }

    /** Token counts as reported by the provider; any of them may be missing. */
    public record Usage(Integer inputTokens, Integer outputTokens, Integer cacheReadTokens, Integer cacheWriteTokens) {
    }
}
