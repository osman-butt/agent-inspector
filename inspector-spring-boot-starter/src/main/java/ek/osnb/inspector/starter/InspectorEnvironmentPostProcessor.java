package ek.osnb.inspector.starter;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * If an Agent Inspector is running, points this app's Spring AI base URLs at its recording
 * proxy. For each provider on the classpath it registers a route named
 * {@code <spring.application.name>-<provider>} that forwards to the base URL the app was
 * configured with (or the provider's default), then overrides the base-url property with
 * {@code <inspector>/proxy/<route>}.
 *
 * <p>When the inspector isn't running nothing changes. Set {@code agent.inspector.enabled=false}
 * to opt out, or {@code agent.inspector.url} if it doesn't run on {@code http://localhost:7070}.
 */
public class InspectorEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String DEFAULT_URL = "http://localhost:7070";

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

    public InspectorEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this(logFactory.getLog(InspectorEnvironmentPostProcessor.class),
                name -> ClassUtils.isPresent(name, InspectorEnvironmentPostProcessor.class.getClassLoader()),
                System::getenv, InspectorConnection::new);
    }

    InspectorEnvironmentPostProcessor(Log log, Predicate<String> classPresent, Function<String, String> envVariables,
                                      Function<String, InspectorConnection> connections) {
        this.log = log;
        this.classPresent = classPresent;
        this.envVariables = envVariables;
        this.connections = connections;
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
        String url = environment.getProperty("agent.inspector.url", DEFAULT_URL).replaceAll("/+$", "");
        InspectorConnection inspector = connections.apply(url);
        if (!inspector.isUp()) {
            log.debug("No Agent Inspector at " + url + ", model traffic goes direct");
            return;
        }

        String app = environment.getProperty("spring.application.name", "app");
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Provider provider : providers) {
            route(environment, inspector, url, app, provider, overrides);
        }
        if (!overrides.isEmpty()) {
            overrides.put("agent.inspector.active", "true");
            overrides.put("agent.inspector.app", app);
            // First, so it wins over application.properties and environment variables.
            environment.getPropertySources().addFirst(new MapPropertySource("agent-inspector", overrides));
        }
    }

    private void route(ConfigurableEnvironment environment, InspectorConnection inspector, String url, String app,
                       Provider provider, Map<String, Object> overrides) {
        String chatUrl = provider.chatProperty() == null ? null : environment.getProperty(provider.chatProperty());
        String property = hasText(chatUrl) ? provider.chatProperty() : provider.property();
        String upstream = firstText(environment.getProperty(property), hasText(chatUrl) ? null : envVariables.apply(provider.envVariable()),
                provider.defaultUrl()).replaceAll("/+$", "");

        if (upstream.startsWith(url) || provider.bypass().stream().anyMatch(upstream::contains)) {
            return; // already proxied by hand, or a setup the proxy would break
        }
        String route = routeName(app + "-" + provider.name());
        if (!inspector.registerRoute(route, upstream)) {
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
