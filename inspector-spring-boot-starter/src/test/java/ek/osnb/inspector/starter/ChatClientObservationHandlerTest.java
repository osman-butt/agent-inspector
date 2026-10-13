package ek.osnb.inspector.starter;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatClientObservationHandlerTest {

    final JsonMapper json = JsonMapper.builder().build();
    final ChatClientRequest appRequest = request(new SystemMessage("Answer briefly."), new UserMessage("And in Oslo?"));
    // What a memory advisor passes on: the earlier turn of the conversation inserted before the new question.
    final ChatClientRequest withHistory = request(new SystemMessage("Answer briefly."),
            new UserMessage("Weather in Copenhagen?"), new AssistantMessage("Windy, 1°C."), new UserMessage("And in Oslo?"));

    FakeInspector inspector;
    InspectorClient client;
    ObservationRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        inspector = FakeInspector.inspector();
        client = new InspectorClient(inspector.url());
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ChatClientObservationHandler(client, "sample"));
    }

    @AfterEach
    void tearDown() {
        client.close();
        inspector.close();
    }

    @Test
    void reportsTheAppsPromptAndEachAdvisorItPassedThrough() throws Exception {
        observe(chatClient(appRequest), () ->
                observe(advisor("MessageChatMemoryAdvisor", -2147482648, appRequest), () ->
                        observe(advisor("ChatModelCallAdvisor", Integer.MAX_VALUE, withHistory), () -> {
                        })));

        JsonNode report = reports(1).getFirst();
        assertThat(report.path("app").stringValue()).isEqualTo("sample");
        assertThat(report.path("parentId").isNull()).isTrue();
        assertThat(report.path("messages").findValuesAsString("text")).containsExactly("Answer briefly.", "And in Oslo?");
        assertThat(report.path("advisors").findValuesAsString("name"))
                .containsExactly("MessageChatMemoryAdvisor", "ChatModelCallAdvisor");
        assertThat(report.path("advisors").path(1).path("messages").findValuesAsString("role"))
                .containsExactly("system", "user", "assistant", "user");
    }

    @Test
    void aCallMadeInsideAnotherCallPointsToItsParent() throws Exception {
        observe(chatClient(appRequest), () -> observe(chatClient(request(new UserMessage("Sub-task"))), () -> {
        }));

        List<JsonNode> reports = reports(2);
        JsonNode inner = reports.get(0);
        JsonNode outer = reports.get(1);
        assertThat(inner.path("parentId").stringValue()).isEqualTo(outer.path("id").stringValue());
    }

    private static ChatClientRequest request(Message... messages) {
        return new ChatClientRequest(new Prompt(List.of(messages)), Map.of());
    }

    private static ChatClientObservationContext chatClient(ChatClientRequest request) {
        return ChatClientObservationContext.builder().request(request).advisors(List.of()).stream(false).build();
    }

    private static AdvisorObservationContext advisor(String name, int order, ChatClientRequest request) {
        return AdvisorObservationContext.builder().advisorName(name).order(order).chatClientRequest(request).build();
    }

    private void observe(Observation.Context context, Runnable work) {
        Observation.createNotStarted("test", () -> context, registry).observe(work);
    }

    /** Posted reports in arrival order, parsed. */
    private List<JsonNode> reports(int count) throws InterruptedException {
        for (int i = 0; i < 40 && inspector.posted.size() < count; i++) {
            Thread.sleep(50);
        }
        return inspector.posted.stream()
                .peek(posted -> assertThat(posted).startsWith("/api/chat-client-calls "))
                .map(posted -> json.readTree(posted.substring(posted.indexOf(' ') + 1)))
                .toList();
    }
}
