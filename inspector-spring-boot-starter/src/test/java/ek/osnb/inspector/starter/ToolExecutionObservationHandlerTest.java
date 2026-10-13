package ek.osnb.inspector.starter;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionObservationHandlerTest {

    final ToolDefinition weather = ToolDefinition.builder().name("weather").description("Weather").inputSchema("{}").build();

    FakeInspector inspector;
    InspectorClient client;
    ObservationRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        inspector = FakeInspector.inspector();
        client = new InspectorClient(inspector.url());
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ToolExecutionObservationHandler(client, "sample"));
    }

    @AfterEach
    void tearDown() {
        client.close();
        inspector.close();
    }

    @Test
    void reportsAToolExecution() throws Exception {
        ToolCallingObservationContext context = context("call_1");
        observe(context, () -> context.setToolCallResult("{\"temperatureCelsius\":1}"));

        assertThat(awaitPosts(1)).singleElement().asString()
                .startsWith("/api/tool-executions ")
                .contains("\"app\":\"sample\"", "\"toolCallId\":\"call_1\"", "\"name\":\"weather\"",
                        "\"arguments\":\"{\\\"city\\\":\\\"Oslo\\\"}\"", "\"error\":null", "\"durationMs\":");
    }

    @Test
    void reportsAFailureThatSpringAiTurnedIntoAResult() throws Exception {
        ToolExecutionExceptionProcessor processor = new ToolFailures.Recording(exception -> exception.getMessage());
        ToolCallingObservationContext context = context("call_2");
        observe(context, () -> context.setToolCallResult(processor.process(
                new ToolExecutionException(weather, new IllegalArgumentException("Unknown city: Atlantis")))));

        assertThat(awaitPosts(1).getFirst()).contains("\"error\":\"IllegalArgumentException: Unknown city: Atlantis\"");
    }

    @Test
    void reportsANestedObservationOfTheSameCallOnce() throws Exception {
        ToolCallingObservationContext outer = context("call_3");
        observe(outer, () -> observe(context("call_3"), () -> {
        }));

        Thread.sleep(300);
        assertThat(awaitPosts(1)).hasSize(1);
    }

    @Test
    void autoConfigurationIsActiveOnlyWhenTheInspectorWasFound() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InspectorAutoConfiguration.class))
                .withBean(ToolExecutionExceptionProcessor.class, () -> DefaultToolExecutionExceptionProcessor.builder().build());

        runner.run(context -> assertThat(context).doesNotHaveBean(InspectorClient.class));
        runner.withPropertyValues("agent.inspector.active=true", "agent.inspector.url=" + inspector.url())
                .run(context -> {
                    assertThat(context).hasSingleBean(InspectorClient.class).hasSingleBean(ObservationRegistry.class);
                    assertThat(context.getBean(ToolExecutionExceptionProcessor.class)).isInstanceOf(ToolFailures.Recording.class);
                });
    }

    private ToolCallingObservationContext context(String toolCallId) {
        return ToolCallingObservationContext.builder()
                .toolDefinition(weather)
                .toolCallId(toolCallId)
                .toolCallArguments("{\"city\":\"Oslo\"}")
                .build();
    }

    private void observe(ToolCallingObservationContext context, Runnable work) {
        Observation.createNotStarted("spring.ai.tool", () -> context, registry).observe(work);
    }

    private List<String> awaitPosts(int count) throws InterruptedException {
        for (int i = 0; i < 40 && inspector.posted.size() < count; i++) {
            Thread.sleep(50);
        }
        return List.copyOf(inspector.posted);
    }
}
