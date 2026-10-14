package io.github.osmanbutt.inspector.starter;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import static io.github.osmanbutt.inspector.starter.FakeInspector.inspector;
import static org.assertj.core.api.Assertions.assertThat;

class InspectorEnvironmentPostProcessorTest {

    static final String OPENAI = "org.springframework.ai.openai.OpenAiChatModel";
    static final String ANTHROPIC = "org.springframework.ai.anthropic.AnthropicChatModel";

    final Map<String, String> envVariables = new HashMap<>();
    final List<Integer> embedAttempts = new ArrayList<>();

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
                    "/api/routes {\"route\":\"my-sample-openai\",\"upstream\":\"http://localhost:12434/engines/v1\",\"app\":\"My Sample\"}");
        }
    }

    @Test
    void forwardsToTheProviderDefaultsWhenNothingIsConfigured() throws Exception {
        try (FakeInspector inspector = inspector()) {
            StandardEnvironment environment = run(Set.of(OPENAI, ANTHROPIC), Map.of("agent.inspector.url", inspector.url()));

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo(inspector.url() + "/proxy/app-openai");
            assertThat(environment.getProperty("spring.ai.anthropic.base-url")).isEqualTo(inspector.url() + "/proxy/app-anthropic");
            assertThat(inspector.posted).containsExactly(
                    "/api/routes {\"route\":\"app-openai\",\"upstream\":\"https://api.openai.com/v1\",\"app\":\"app\"}",
                    "/api/routes {\"route\":\"app-anthropic\",\"upstream\":\"https://api.anthropic.com\",\"app\":\"app\"}");
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
    void neverStartsAnInspectorWhenNothingWouldBeRouted() {
        run(Set.of(OPENAI), Map.of(
                "agent.inspector.url", "http://127.0.0.1:1",
                "spring.ai.openai.base-url", "https://my-resource.openai.azure.com/openai/v1"));

        assertThat(embedAttempts).isEmpty();
    }

    @Test
    void usesAnotherRouteNameWhenAnotherAppsUpstreamHasIt() throws Exception {
        try (FakeInspector inspector = inspector()) {
            inspector.taken.add("app-openai");
            StandardEnvironment environment = run(Set.of(OPENAI), Map.of("agent.inspector.url", inspector.url()));

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo(inspector.url() + "/proxy/app-openai-2");
            assertThat(inspector.posted).containsExactly(
                    "/api/routes {\"route\":\"app-openai\",\"upstream\":\"https://api.openai.com/v1\",\"app\":\"app\"}",
                    "/api/routes {\"route\":\"app-openai-2\",\"upstream\":\"https://api.openai.com/v1\",\"app\":\"app\"}");
        }
    }

    @Test
    void goesDirectWhenEveryRouteNameIsTaken() throws Exception {
        try (FakeInspector inspector = inspector()) {
            inspector.taken.add("app-openai");
            for (int number = 2; number <= 9; number++) {
                inspector.taken.add("app-openai-" + number);
            }
            StandardEnvironment environment = run(Set.of(OPENAI), Map.of("agent.inspector.url", inspector.url()));

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isNull();
            assertThat(environment.getProperty("agent.inspector.active")).isNull();
            assertThat(inspector.posted).hasSize(9);
        }
    }

    @Test
    void changesNothingWhenNoInspectorRunsOrCanBeStarted() {
        StandardEnvironment environment = run(Set.of(OPENAI), Map.of(
                "agent.inspector.url", "http://127.0.0.1:1",
                "spring.ai.openai.base-url", "http://localhost:12434/engines/v1"));

        assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("http://localhost:12434/engines/v1");
        assertThat(environment.getProperty("agent.inspector.active")).isNull();
        assertThat(embedAttempts).as("tried the configured port and the next ones").startsWith(1, 2);
    }

    @Test
    void startsAnInspectorInsideTheAppWhenNoneRuns() throws Exception {
        String url = "http://localhost:" + freePort();
        try {
            StandardEnvironment environment = run(Set.of(OPENAI), Map.of(
                    "agent.inspector.url", url,
                    "spring.ai.openai.base-url", "http://localhost:12434/engines/v1"), EmbeddedInspector::start);

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo(url + "/proxy/app-openai");
            assertThat(environment.getProperty("agent.inspector.url")).isEqualTo(url);
            InspectorConnection.Ping ping = new InspectorConnection(url).ping();
            assertThat(ping.embedded()).isTrue();
            assertThat(ping.pid()).isEqualTo(ProcessHandle.current().pid());
            HttpResponse<String> ui = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ui.body()).contains("<title>Agent Inspector</title>");

            // After a DevTools restart, the app finds the inspector it started and uses it again.
            StandardEnvironment restarted = run(Set.of(OPENAI), Map.of(
                    "agent.inspector.url", url,
                    "spring.ai.openai.base-url", "http://localhost:12434/engines/v1"), port -> {
                throw new AssertionError("started a second inspector");
            });
            assertThat(restarted.getProperty("spring.ai.openai.base-url")).isEqualTo(url + "/proxy/app-openai");
        } finally {
            EmbeddedInspector.stop();
        }
    }

    @Test
    void watchesAnInspectorItStartedEvenWhenNoRouteWasAccepted() throws Exception {
        String url = "http://localhost:" + freePort();
        try {
            // The inspector refuses an upstream that isn't http(s), so nothing is routed.
            StandardEnvironment environment = run(Set.of(OPENAI), Map.of(
                    "agent.inspector.url", url,
                    "spring.ai.openai.base-url", "ftp://example.invalid"), EmbeddedInspector::start);

            assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("ftp://example.invalid");
            assertThat(EmbeddedInspector.isRunning()).isTrue();
            assertThat(EmbeddedInspector.isWatching()).isTrue();
        } finally {
            EmbeddedInspector.stop();
        }
    }

    @Test
    void doesNotUseAnotherAppsEmbeddedInspector() throws Exception {
        try (FakeInspector otherApp = new FakeInspector("{\"name\":\"agent-inspector\",\"api\":1,\"embedded\":true,\"pid\":1}")) {
            int port = URI.create(otherApp.url()).getPort();
            run(Set.of(OPENAI), Map.of("agent.inspector.url", otherApp.url()));

            assertThat(otherApp.posted).isEmpty();
            assertThat(embedAttempts).isNotEmpty().allMatch(attempt -> attempt > port);
        }
    }

    @Test
    void neverStartsAnInspectorInTestsOrWhenTurnedOffOrForARemoteUrl() {
        run(Set.of(OPENAI), Map.of("agent.inspector.url", "http://127.0.0.1:1",
                "org.springframework.boot.test.context.SpringBootTestContextBootstrapper", "true"));
        run(Set.of(OPENAI), Map.of("agent.inspector.url", "http://127.0.0.1:1",
                "agent.inspector.embedded.enabled", "false"));
        run(Set.of(OPENAI), Map.of("agent.inspector.url", "http://inspector.invalid:7070"));

        assertThat(embedAttempts).isEmpty();
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
        assertThat(InspectorEnvironmentPostProcessor.routeName("app-openai", 1)).isEqualTo("app-openai");
        assertThat(InspectorEnvironmentPostProcessor.routeName("app-openai", 2)).isEqualTo("app-openai-2");
        String longest = "a".repeat(63);
        assertThat(InspectorEnvironmentPostProcessor.routeName(longest, 9)).hasSize(63).endsWith("a-9");
    }

    /** Runs the post-processor; asked to start an embedded inspector, it only records the port. */
    private StandardEnvironment run(Set<String> classesOnClasspath, Map<String, Object> properties) {
        return run(classesOnClasspath, properties, port -> {
            embedAttempts.add(port);
            return false;
        });
    }

    private StandardEnvironment run(Set<String> classesOnClasspath, Map<String, Object> properties, IntPredicate embed) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new InspectorEnvironmentPostProcessor(LogFactory.getLog(getClass()), classesOnClasspath::contains,
                envVariables::get, InspectorConnection::new, embed)
                .postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
