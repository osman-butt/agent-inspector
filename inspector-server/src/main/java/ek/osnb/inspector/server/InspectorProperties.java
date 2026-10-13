package ek.osnb.inspector.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.util.Map;

/**
 * @param routes       proxy routes: {@code /proxy/<name>/**} is forwarded to the upstream base URL
 * @param maxExchanges how many exchanges to keep in memory; the oldest are dropped first
 * @param maxBodySize  request and response bodies are stored up to this size
 */
@ConfigurationProperties("agent.inspector")
public record InspectorProperties(
        Map<String, URI> routes,
        @DefaultValue("500") int maxExchanges,
        @DefaultValue("5MB") DataSize maxBodySize) {

    public InspectorProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
    }
}
