package io.github.osmanbutt.inspector.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @param routes       proxy routes: {@code /proxy/<name>/**} is forwarded to the upstream base URL.
 *                     Added to {@link #DEFAULT_ROUTES}; a route with the same name replaces the default.
 * @param maxExchanges how many exchanges to keep in memory; the oldest are dropped first
 * @param maxBodySize  request and response bodies are stored up to this size
 */
@ConfigurationProperties("agent.inspector")
public record InspectorProperties(
        Map<String, URI> routes,
        @DefaultValue("500") int maxExchanges,
        @DefaultValue("5MB") DataSize maxBodySize) {

    public static final Map<String, URI> DEFAULT_ROUTES = Map.of(
            "openai", URI.create("https://api.openai.com/v1"),
            "anthropic", URI.create("https://api.anthropic.com"),
            "dmr", URI.create("http://localhost:12434/engines/v1"));

    public InspectorProperties {
        Map<String, URI> merged = new LinkedHashMap<>(DEFAULT_ROUTES);
        if (routes != null) {
            merged.putAll(routes);
        }
        routes = Map.copyOf(merged);
    }
}
