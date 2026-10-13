package io.github.osmanbutt.inspector.sample;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Tests never route through an inspector that happens to be running.
@SpringBootTest(properties = "agent.inspector.enabled=false")
class SampleApplicationTests {

    @Test
    void contextLoads() {
    }
}
