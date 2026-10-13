package ek.osnb.inspector.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class WebConfigTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void servesTheUi() {
        assertThat(mvc.get().uri("/")).hasStatusOk().hasForwardedUrl("/index.html");
        assertThat(mvc.get().uri("/index.html")).hasStatusOk().bodyText().contains("<title>Agent Inspector</title>");
        assertThat(mvc.get().uri("/js/main.js")).hasStatusOk().bodyText().contains("EventSource");
    }

    @Test
    void hasTheDefaultRoutesPlusConfiguredOnes() {
        InspectorProperties properties = new InspectorProperties(
                Map.of("dmr", URI.create("http://other:1234/v1"), "mine", URI.create("http://mine")), 10, null);

        assertThat(properties.routes()).containsKeys("openai", "anthropic", "mine");
        assertThat(properties.routes().get("dmr")).hasToString("http://other:1234/v1");
    }
}
