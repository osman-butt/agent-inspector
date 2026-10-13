package ek.osnb.inspector.starter;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static ek.osnb.inspector.starter.FakeInspector.inspector;
import static org.assertj.core.api.Assertions.assertThat;

class InspectorEnvironmentPostProcessorTest {

    static final String OPENAI = "org.springframework.ai.openai.OpenAiChatModel";
    static final String ANTHROPIC = "org.springframework.ai.anthropic.AnthropicChatModel";

    final Map<String, String> envVariables = new HashMap<>();

    @Test
    void routesDockerModelRunnerThroughTheInspector() throws Exception {
        try (FakeInspector inspector = inspector()) {
            StandardEnvironment environment = run(Set.of(OPENAI), Map.of(
                    "agent.inspector.url", inspector.url(),
                    "spring.application.name", "My Sample",
                    "spring.ai.openai.base-url", "http://localhost:12434/engines/v1"));

            assertThat(environment.getProperty("spring.ai.openai.base-url"))
                    .isEqualTo(inspector.url() + "/proxy/my-sample-openai");
            assertThat(environment.getProperty("agent.inspector.active")).isEqualTo("true");
            assertThat(inspector.posted).containsExactly(
                    "/api/routes {\"route\":\"my-sample-openai\",\"upstream\":\"http://localhost:12434/engines/v1\"}");
        }
    }

    @Test
    void forwardsToTheProviderDefaultsWhenNothingIsConfigured() throws Exception {
        try (FakeInspector inspector = inspector()) {
            StandardEnvironment environment = run(Set.of(OPENAI, ANTHROPIC), Map.of("agent.inspector.url", inspector.url()));

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo(inspector.url() + "/proxy/app-openai");
            assertThat(environment.getProperty("spring.ai.anthropic.base-url")).isEqualTo(inspector.url() + "/proxy/app-anthropic");
            assertThat(inspector.posted).containsExactly(
                    "/api/routes {\"route\":\"app-openai\",\"upstream\":\"https://api.openai.com/v1\"}",
                    "/api/routes {\"route\":\"app-anthropic\",\"upstream\":\"https://api.anthropic.com\"}");
        }
    }

    @Test
    void usesTheBaseUrlFromTheEnvironmentVariable() throws Exception {
        envVariables.put("OPENAI_BASE_URL", "http://gateway.local/v1/");
        try (FakeInspector inspector = inspector()) {
            run(Set.of(OPENAI), Map.of("agent.inspector.url", inspector.url()));

            assertThat(inspector.posted.getFirst()).contains("\"upstream\":\"http://gateway.local/v1\"");
        }
    }

    @Test
    void theChatBaseUrlWinsOverTheCommonOne() throws Exception {
        try (FakeInspector inspector = inspector()) {
            StandardEnvironment environment = run(Set.of(OPENAI), Map.of(
                    "agent.inspector.url", inspector.url(),
                    "spring.ai.openai.base-url", "https://api.openai.com/v1",
                    "spring.ai.openai.chat.base-url", "http://localhost:12434/engines/v1"));

            assertThat(environment.getProperty("spring.ai.openai.chat.base-url")).isEqualTo(inspector.url() + "/proxy/app-openai");
            assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("https://api.openai.com/v1");
        }
    }

    @Test
    void leavesAzureAndHandRoutedUrlsAlone() throws Exception {
        try (FakeInspector inspector = inspector()) {
            StandardEnvironment environment = run(Set.of(OPENAI, ANTHROPIC), Map.of(
                    "agent.inspector.url", inspector.url(),
                    "spring.ai.openai.base-url", "https://my-resource.openai.azure.com/openai/v1",
                    "spring.ai.anthropic.base-url", inspector.url() + "/proxy/anthropic"));

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("https://my-resource.openai.azure.com/openai/v1");
            assertThat(environment.getProperty("spring.ai.anthropic.base-url")).isEqualTo(inspector.url() + "/proxy/anthropic");
            assertThat(environment.getProperty("agent.inspector.active")).isNull();
            assertThat(inspector.posted).isEmpty();
        }
    }

    @Test
    void changesNothingWhenTheInspectorIsNotRunning() {
        StandardEnvironment environment = run(Set.of(OPENAI), Map.of(
                "agent.inspector.url", "http://127.0.0.1:1",
                "spring.ai.openai.base-url", "http://localhost:12434/engines/v1"));

        assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("http://localhost:12434/engines/v1");
        assertThat(environment.getProperty("agent.inspector.active")).isNull();
    }

    @Test
    void ignoresAnotherServiceOnThePort() throws Exception {
        try (FakeInspector somethingElse = new FakeInspector("{\"status\":\"UP\"}")) {
            StandardEnvironment environment = run(Set.of(OPENAI), Map.of("agent.inspector.url", somethingElse.url()));

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isNull();
            assertThat(somethingElse.posted).isEmpty();
        }
    }

    @Test
    void canBeSwitchedOff() throws Exception {
        try (FakeInspector inspector = inspector()) {
            StandardEnvironment environment = run(Set.of(OPENAI), Map.of(
                    "agent.inspector.url", inspector.url(),
                    "agent.inspector.enabled", "false"));

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isNull();
            assertThat(inspector.posted).isEmpty();
        }
    }

    @Test
    void routeNamesAreSafe() {
        assertThat(InspectorEnvironmentPostProcessor.routeName("My Sample_App-openai")).isEqualTo("my-sample-app-openai");
        assertThat(InspectorEnvironmentPostProcessor.routeName("--x--")).isEqualTo("x");
    }

    private StandardEnvironment run(Set<String> classesOnClasspath, Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new InspectorEnvironmentPostProcessor(LogFactory.getLog(getClass()), classesOnClasspath::contains,
                envVariables::get, InspectorConnection::new)
                .postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
