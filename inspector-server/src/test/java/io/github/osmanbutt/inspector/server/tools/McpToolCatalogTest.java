package io.github.osmanbutt.inspector.server.tools;

import io.github.osmanbutt.inspector.server.tools.McpToolCatalog.McpToolsChanged;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {

    final List<Object> events = new ArrayList<>();
    final McpToolCatalog catalog = new McpToolCatalog(events::add);

    @Test
    void theUiOnlyHearsAboutRealChanges() {
        catalog.save("sample", Map.of("weather", "weather-server"));
        catalog.save("sample", Map.of("weather", "weather-server")); // after the next tool call
        catalog.save("sample", Map.of("weather", "weather-server", "forecast", "weather-server"));

        assertThat(events).containsExactly(new McpToolsChanged("sample"), new McpToolsChanged("sample"));
        assertThat(catalog.forApp("sample")).containsOnlyKeys("weather", "forecast");
        assertThat(catalog.forApp("other")).isEmpty();
    }
}
