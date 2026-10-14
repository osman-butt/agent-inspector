package io.github.osmanbutt.inspector.server.session;

import io.github.osmanbutt.inspector.server.api.ExchangeSummary;
import io.github.osmanbutt.inspector.server.chatclient.ChatClientCall;
import io.github.osmanbutt.inspector.server.tools.ToolExecution;
import io.github.osmanbutt.inspector.server.wire.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * A session as a timeline of round-trips, each showing only what it added.
 *
 * @param toolExecutions  what the app reported about running the tools, by tool call id
 * @param chatClientCalls the ChatClient calls that made these round-trips, as reported by the app
 * @param mcpTools        the app's tools that come from MCP servers: tool name to server
 */
public record SessionDetail(
        SessionSummary summary,
        List<Turn> turns,
        Map<String, ToolExecution> toolExecutions,
        List<ChatClientCall> chatClientCalls,
        Map<String, String> mcpTools) {

    /**
     * @param resent      how many request messages were sent before and are repeated here
     * @param newMessages the request messages after those
     * @param output           the model's answer, {@code null} while pending or on error
     * @param chatClientCallId the ChatClient call that sent this round-trip, if the app reported one
     */
    public record Turn(
            ExchangeSummary exchange,
            int resent,
            List<ChatMessage> newMessages,
            ChatMessage output,
            String finishReason,
            String error,
            String chatClientCallId) {
    }
}
