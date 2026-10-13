package io.github.osmanbutt.inspector.starter;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Points this app's Spring AI base URLs at an Agent Inspector's recording proxy. For each
 * provider on the classpath it registers a route named {@code <spring.application.name>-<provider>}
 * that forwards to the base URL the app was configured with (or the provider's default), then
 * overrides the base-url property with {@code <inspector>/proxy/<route>}.
 *
 * <p>It uses the inspector at {@code agent.inspector.url} (default {@code http://localhost:7070})
 * when one runs there. Otherwise it starts one inside this app, on that port or the next free one
 * of the nine after it, so adding the starter is all it takes. Set
 * {@code agent.inspector.embedded.enabled=false} to only use a running inspector, or
 * {@code agent.inspector.enabled=false} to opt out completely.
 */
public class InspectorEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String DEFAULT_URL = "http://localhost:7070";

    /** Added by {@code @SpringBootTest}: tests never start an inspector. */
    private static final String TEST_CONTEXT = "org.springframework.boot.test.context.SpringBootTestContextBootstrapper";

    /** Ports tried after the configured one when it is taken. */
    private static final int MORE_PORTS = 9;

    private record Inspector(String url, InspectorConnection connection, boolean embedded) {
    }

    /**
     * @param chatProperty a chat-specific base-url that wins over the common one when set
     * @param envVariable  read by the provider's SDK when the property isn't set
     * @param bypass       base URLs that need provider-specific handling and must not be proxied
     */
    private record Provider(String name, String markerClass, String property, String chatProperty,
                            String envVariable, String defaultUrl, List<String> bypass) {
    }

    private static final List<Provider> PROVIDERS = List.of(
            // Also Docker Model Runner, Ollama's /v1 and other OpenAI-compatible servers.
            // Azure and GitHub Models are detected from their URLs by Spring AI, so they are left alone.
            new Provider("openai", "org.springframework.ai.openai.OpenAiChatModel",
                    "spring.ai.openai.base-url", "spring.ai.openai.chat.base-url", "OPENAI_BASE_URL",
                    "https://api.openai.com/v1", List.of(".azure.com", "models.github.ai", "models.inference.ai.azure.com")),
            new Provider("anthropic", "org.springframework.ai.anthropic.AnthropicChatModel",
                    "spring.ai.anthropic.base-url", null, "ANTHROPIC_BASE_URL",
                    "https://api.anthropic.com", List.of()));

    private final Log log;
    private final Predicate<String> classPresent;
    private final Function<String, String> envVariables;
    private final Function<String, InspectorConnection> connections;
    private final IntPredicate embeddedInspector;

    public InspectorEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this(logFactory.getLog(InspectorEnvironmentPostProcessor.class),
                name -> ClassUtils.isPresent(name, InspectorEnvironmentPostProcessor.class.getClassLoader()),
                System::getenv, InspectorConnection::new, EmbeddedInspector::start);
    }

    /**
     * @param embeddedInspector starts an inspector inside this app on the given port; true when it did
     */
    InspectorEnvironmentPostProcessor(Log log, Predicate<String> classPresent, Function<String, String> envVariables,
                                      Function<String, InspectorConnection> connections, IntPredicate embeddedInspector) {
        this.log = log;
        this.classPresent = classPresent;
        this.envVariables = envVariables;
        this.connections = connections;
        this.embeddedInspector = embeddedInspector;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("agent.inspector.enabled", Boolean.class, true)) {
            return;
        }
        List<Provider> providers = PROVIDERS.stream().filter(provider -> classPresent.test(provider.markerClass())).toList();
        if (providers.isEmpty()) {
            return;
        }
        String configured = environment.getProperty("agent.inspector.url", DEFAULT_URL).replaceAll("/+$", "");
        Inspector inspector = find(environment, configured);
        if (inspector == null) {
            log.debug("No Agent Inspector at " + configured + ", model traffic goes direct");
            return;
        }

        String app = environment.getProperty("spring.application.name", "app");
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Provider provider : providers) {
            route(environment, inspector, configured, app, provider, overrides);
        }
        if (!overrides.isEmpty()) {
            overrides.put("agent.inspector.active", "true");
            overrides.put("agent.inspector.app", app);
            // Where tool and ChatClient reports go: the inspector found or started here.
            overrides.put("agent.inspector.url", inspector.url());
            // First, so it wins over application.properties and environment variables.
            environment.getPropertySources().addFirst(new MapPropertySource("agent-inspector", overrides));
            log.info("Agent Inspector UI: " + inspector.url() + (inspector.embedded() ? " (running in this app)" : " (shared)"));
        }
    }

    /**
     * The inspector at the configured URL if it can be used. Otherwise, when allowed, one on the
     * configured port or one of the next ones: a usable inspector already there, or a new one
     * started inside this app on the first free port.
     */
    private Inspector find(ConfigurableEnvironment environment, String configured) {
        InspectorConnection connection = connections.apply(configured);
        InspectorConnection.Ping ping = connection.ping();
        if (ping.usable()) {
            return new Inspector(configured, connection, ping.embedded());
        }
        URI uri = URI.create(configured);
        if (!mayEmbed(environment, uri)) {
            return null;
        }
        int first = uri.getPort() == -1 ? 80 : uri.getPort();
        for (int port = first; port <= first + MORE_PORTS; port++) {
            String url = uri.getScheme() + "://" + uri.getHost() + ":" + port;
            InspectorConnection candidate = port == first ? connection : connections.apply(url);
            InspectorConnection.Ping answer = port == first ? ping : candidate.ping();
            if (answer.usable()) {
                return new Inspector(url, candidate, answer.embedded());
            }
            if (answer.state() == InspectorConnection.State.FREE && startEmbedded(port)) {
                return new Inspector(url, candidate, true);
            }
        }
        log.warn("Agent Inspector: ports " + first + "-" + (first + MORE_PORTS) + " are taken, model traffic goes direct");
        return null;
    }

    private boolean mayEmbed(ConfigurableEnvironment environment, URI uri) {
        return environment.getProperty("agent.inspector.embedded.enabled", Boolean.class, true)
                && !environment.containsProperty(TEST_CONTEXT)
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    }

    private boolean startEmbedded(int port) {
        try {
            return embeddedInspector.test(port);
        } catch (RuntimeException e) {
            log.warn("Agent Inspector could not start on port " + port + ": " + e.getMessage());
            return false;
        }
    }

    private void route(ConfigurableEnvironment environment, Inspector inspector, String configured, String app,
                       Provider provider, Map<String, Object> overrides) {
        String chatUrl = provider.chatProperty() == null ? null : environment.getProperty(provider.chatProperty());
        String property = hasText(chatUrl) ? provider.chatProperty() : provider.property();
        String upstream = firstText(environment.getProperty(property), hasText(chatUrl) ? null : envVariables.apply(provider.envVariable()),
                provider.defaultUrl()).replaceAll("/+$", "");

        if (upstream.startsWith(configured) || upstream.startsWith(inspector.url())
                || provider.bypass().stream().anyMatch(upstream::contains)) {
            return; // already proxied by hand, or a setup the proxy would break
        }
        String route = routeName(app + "-" + provider.name());
        String url = inspector.url();
        if (!inspector.connection().registerRoute(route, upstream, app)) {
            log.warn("Agent Inspector at " + url + " did not accept route " + route + ", " + provider.name() + " goes direct");
            return;
        }
        overrides.put(property, url + "/proxy/" + route);
        log.info("Agent Inspector: " + provider.name() + " traffic goes through " + url + "/proxy/" + route
                + " to " + upstream);
    }

    /** Lowercase letters, digits and dashes, as the inspector expects. */
    static String routeName(String name) {
        String route = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return route.length() > 63 ? route.substring(0, 63) : route;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
