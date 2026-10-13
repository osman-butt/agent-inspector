package ek.osnb.inspector.starter;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Reports each {@code ChatClient} call: the prompt as the app wrote it, then every advisor it
 * passed through (memory, RAG, guardrails, ...) with the messages that advisor received. The
 * inspector compares those with the wire to show what each advisor added. Uses the observations
 * Spring AI already emits, so no advisor has to be added to the app.
 */
public class ChatClientObservationHandler implements ObservationHandler<Observation.Context> {

    public record MessageView(String role, String text) {
    }

    public record AdvisorStep(String name, int order, long durationMs, List<MessageView> messages) {
    }

    public record ChatClientCallReport(String id, String parentId, String app, Instant startedAt, long durationMs,
                                       boolean stream, List<MessageView> messages, List<AdvisorStep> advisors,
                                       String answer, String error) {
    }

    static final int MAX_TEXT_LENGTH = 4_000;

    /** State of a running call, kept on its observation context. */
    private static final class Call {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final Instant startedAt = Instant.now();
        final long nanos = System.nanoTime();
        final String parentId;
        final List<MessageView> messages;
        final List<Step> steps = Collections.synchronizedList(new ArrayList<>());

        Call(String parentId, List<MessageView> messages) {
            this.parentId = parentId;
            this.messages = messages;
        }
    }

    private static final class Step {
        final String name;
        final int order;
        final List<MessageView> messages;
        final long nanos = System.nanoTime();
        volatile long durationMs;

        Step(String name, int order, List<MessageView> messages) {
            this.name = name;
            this.order = order;
            this.messages = messages;
        }
    }

    private final InspectorClient client;
    private final String app;

    public ChatClientObservationHandler(InspectorClient client, String app) {
        this.client = client;
        this.app = app;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatClientObservationContext || context instanceof AdvisorObservationContext;
    }

    @Override
    public void onStart(Observation.Context context) {
        if (context instanceof ChatClientObservationContext call) {
            Call parent = enclosingCall(call);
            call.put(Call.class, new Call(parent == null ? null : parent.id, messages(call.getRequest().prompt().getInstructions())));
        } else if (context instanceof AdvisorObservationContext advisor) {
            Call call = enclosingCall(advisor);
            if (call != null) {
                Step step = new Step(advisor.getAdvisorName(), advisor.getOrder(),
                        messages(advisor.getChatClientRequest().prompt().getInstructions()));
                call.steps.add(step);
                advisor.put(Step.class, step);
            }
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        if (context instanceof AdvisorObservationContext advisor) {
            Step step = advisor.get(Step.class);
            if (step != null) {
                step.durationMs = millisSince(step.nanos);
            }
        } else if (context instanceof ChatClientObservationContext observed) {
            Call call = observed.get(Call.class);
            if (call == null) {
                return;
            }
            List<AdvisorStep> advisors;
            synchronized (call.steps) {
                advisors = call.steps.stream()
                        .map(step -> new AdvisorStep(step.name, step.order, step.durationMs, step.messages))
                        .toList();
            }
            client.post("/api/chat-client-calls", new ChatClientCallReport(call.id, call.parentId, app, call.startedAt,
                    millisSince(call.nanos), observed.isStream(), call.messages, advisors, answer(observed.getResponse()),
                    observed.getError() == null ? null
                            : observed.getError().getClass().getSimpleName() + ": " + observed.getError().getMessage()));
        }
    }

    /** The nearest ChatClient call above this observation: advisors run nested inside it, and so do sub-agents. */
    private static Call enclosingCall(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        while (parent != null) {
            if (parent.getContextView() instanceof ChatClientObservationContext call && call.get(Call.class) != null) {
                return call.get(Call.class);
            }
            parent = parent.getContextView().getParentObservation();
        }
        return null;
    }

    private static List<MessageView> messages(List<Message> messages) {
        return messages.stream().map(ChatClientObservationHandler::message).toList();
    }

    /** Text as the app sees it, with tool calls and results written out so they can be compared too. */
    private static MessageView message(Message message) {
        StringBuilder text = new StringBuilder(message.getText() == null ? "" : message.getText());
        if (message instanceof AssistantMessage assistant) {
            assistant.getToolCalls().forEach(call ->
                    text.append(text.isEmpty() ? "" : "\n").append("→ ").append(call.name()).append(call.arguments()));
        } else if (message instanceof ToolResponseMessage tool) {
            tool.getResponses().forEach(response ->
                    text.append(text.isEmpty() ? "" : "\n").append("← ").append(response.name()).append(": ").append(response.responseData()));
        }
        String value = text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) + "…" : text.toString();
        return new MessageView(message.getMessageType().getValue(), value);
    }

    private static String answer(ChatClientResponse response) {
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private static long millisSince(long nanos) {
        return Duration.ofNanos(System.nanoTime() - nanos).toMillis();
    }
}
