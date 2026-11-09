package io.github.osmanbutt.inspector.server;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "agent.inspector.allowed-hosts=inspector.example.com")
@AutoConfigureMockMvc
class HostCheckFilterTest {

    @Autowired
    MockMvcTester mvc;

    @ParameterizedTest
    @ValueSource(strings = {"localhost:7070", "LOCALHOST", "app.localhost:7070", "127.0.0.1:7070", "[::1]:7070",
            "192.168.1.20", "inspector:7070", "inspector.example.com:7070", "Inspector.Example.com."})
    void answersRequestsForThisMachine(String host) {
        assertThat(mvc.get().uri("/api/ping").header("Host", host)).hasStatusOk();
    }

    /** What a web page sends after DNS rebinding: its own domain, now resolving to 127.0.0.1. */
    @ParameterizedTest
    @ValueSource(strings = {"evil.example:7070", "evil.example", "127.0.0.1.evil.example", "localhost.evil.example"})
    void refusesRequestsForOtherNames(String host) {
        assertThat(mvc.get().uri("/api/exchanges").header("Host", host))
                .hasStatus(403)
                .bodyText().contains("agent.inspector.allowed-hosts");
        assertThat(mvc.post().uri("/proxy/dmr/chat/completions").header("Host", host)).hasStatus(403);
        assertThat(mvc.get().uri("/index.html").header("Host", host)).hasStatus(403);
    }
}
