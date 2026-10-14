package io.github.osmanbutt.inspector.server.tools;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of each app's tools come from MCP servers, as the starter reports them, so the UI can
 * tell them apart from the app's own tools. It describes the apps, not their traffic, so
 * clearing the recorded traffic keeps it.
 */
@Component
public class McpToolCatalog {

    public record McpToolsChanged(String app) {
    }

    private final Map<String, Map<String, String>> serversByApp = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;

    public McpToolCatalog(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * Replaces the app's MCP tools. The starter sends them after every tool call, so the catalog
     * comes back after an inspector restart; the UI only hears about real changes.
     *
     * @param servers tool name, as the model sees it, to the MCP server it comes from ({@code ""} when unknown)
     */
    public void save(String app, Map<String, String> servers) {
        Map<String, String> copy = Map.copyOf(servers);
        if (!copy.equals(serversByApp.put(app, copy))) {
            events.publishEvent(new McpToolsChanged(app));
        }
    }

    /** Tool name to MCP server; empty for an app without MCP tools. */
    public Map<String, String> forApp(String app) {
        return serversByApp.getOrDefault(app, Map.of());
    }
}
