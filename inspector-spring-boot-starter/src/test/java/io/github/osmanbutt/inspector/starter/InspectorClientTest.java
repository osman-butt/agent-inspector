package io.github.osmanbutt.inspector.starter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;

class InspectorClientTest {

    /** A tool or ChatClient call that ends while the application shuts down still reports, and must not fail. */
    @Test
    void reportingAfterCloseNeverThrows() {
        InspectorClient client = new InspectorClient("http://127.0.0.1:1");
        client.close();

        assertThatNoException().isThrownBy(() -> client.post("/api/tool-executions", Map.of("name", "weather")));
        assertThatNoException().isThrownBy(() -> client.postLater("/api/mcp-tools", () -> Map.of("app", "sample")));
    }
}
