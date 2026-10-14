package io.github.osmanbutt.inspector.server.api;

import io.github.osmanbutt.inspector.server.tools.McpToolCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The starter reports here which of an app's tools come from MCP servers. */
@RestController
@RequestMapping("/api/mcp-tools")
public class McpToolController {

    /**
     * @param name   the tool's name as the model sees it
     * @param server the MCP server it comes from, blank when the starter couldn't tell
     */
    public record McpTool(String name, String server) {
    }

    public record McpToolsReport(String app, List<McpTool> tools) {
    }

    private final McpToolCatalog catalog;

    public McpToolController(McpToolCatalog catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@RequestBody McpToolsReport report) {
        if (report.app() == null || report.tools() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "app and tools are required");
        }
        Map<String, String> servers = new LinkedHashMap<>();
        for (McpTool tool : report.tools()) {
            if (tool != null && tool.name() != null) {
                servers.put(tool.name(), tool.server() == null ? "" : tool.server());
            }
        }
        catalog.save(report.app(), servers);
    }
}
