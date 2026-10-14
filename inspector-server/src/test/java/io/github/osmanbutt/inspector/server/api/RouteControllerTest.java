package io.github.osmanbutt.inspector.server.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class RouteControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void aRouteNameForwardsToOneUpstreamOnly() {
        String first = """
                {"route": "route-test-openai", "upstream": "https://api.openai.com/v1", "app": "app"}
                """;
        assertThat(mvc.post().uri("/api/routes").contentType("application/json").content(first)).hasStatusOk();
        // The same app again, after a restart.
        assertThat(mvc.post().uri("/api/routes").contentType("application/json").content(first)).hasStatusOk();

        // Another app with the same name but another upstream must not take over the route.
        assertThat(mvc.post().uri("/api/routes").contentType("application/json").content("""
                {"route": "route-test-openai", "upstream": "http://gateway.example/v1", "app": "app"}
                """)).hasStatus(409);

        assertThat(mvc.get().uri("/api/routes"))
                .bodyJson()
                .extractingPath("$['route-test-openai']").isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void refusesAnUpstreamThatIsNotHttp() {
        assertThat(mvc.post().uri("/api/routes").contentType("application/json").content("""
                {"route": "route-test-ftp", "upstream": "ftp://example.invalid", "app": "app"}
                """)).hasStatus(400);
    }
}
