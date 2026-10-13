package ek.osnb.inspector.server.chatclient;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * One {@code ChatClient} call inside an application, as reported by the starter: the prompt
 * the app wrote, and the advisors it passed through on its way to the model. The wire shows
 * the prompt after all of them; comparing the two shows what memory, RAG and other advisors
 * added.
 *
 * @param parentId the enclosing ChatClient call, for a call made from inside another (a sub-agent)
 * @param messages the prompt exactly as the app wrote it
 * @param advisors in the order they ran; each with the messages it received
 */
public record ChatClientCall(
        String id,
        String parentId,
        String app,
        Instant startedAt,
        long durationMs,
        boolean stream,
        List<MessageView> messages,
        List<AdvisorStep> advisors,
        String answer,
        String error) {

    public record MessageView(String role, String text) {
    }

    public record AdvisorStep(String name, int order, long durationMs, List<MessageView> messages) {
    }

    @JsonIgnore
    public Instant endedAt() {
        return startedAt.plusMillis(durationMs);
    }
}
