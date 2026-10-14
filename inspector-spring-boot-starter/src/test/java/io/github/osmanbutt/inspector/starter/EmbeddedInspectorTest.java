package io.github.osmanbutt.inspector.starter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EmbeddedInspectorTest {

    @AfterEach
    void stop() {
        EmbeddedInspector.stop();
    }

    @Test
    void startsAnEmbeddedInspectorAndStopsIt() throws Exception {
        int port = freePort();

        assertThat(EmbeddedInspector.start(port)).isTrue();

        InspectorConnection.Ping ping = new InspectorConnection("http://localhost:" + port).ping();
        assertThat(ping.state()).isEqualTo(InspectorConnection.State.INSPECTOR);
        assertThat(ping.embedded()).isTrue();

        EmbeddedInspector.stop();
        assertThat(new InspectorConnection("http://localhost:" + port).ping().state())
                .isEqualTo(InspectorConnection.State.FREE);
    }

    @Test
    void webServerThreadsLiveInTheInspectorGroup() throws Exception {
        EmbeddedInspector.start(freePort());

        long outside = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("container-") || thread.getName().startsWith("Catalina-utility"))
                .filter(thread -> thread.getThreadGroup() != EmbeddedInspector.GROUP)
                .count();

        assertThat(outside).isZero();
    }

    @Test
    void stopsOnceTheHostHasNothingLeftRunning() throws Exception {
        EmbeddedInspector.start(freePort());
        AtomicBoolean hostAlive = new AtomicBoolean(true);
        EmbeddedInspector.stopWhenHostEnds(hostAlive::get, Duration.ofMillis(20));

        Thread.sleep(200);
        assertThat(EmbeddedInspector.isRunning()).isTrue();

        hostAlive.set(false);
        await().atMost(Duration.ofSeconds(5)).until(() -> !EmbeddedInspector.isRunning());
    }

    @Test
    void stopEndsTheWatcherSoTheNextInspectorGetsItsOwn() throws Exception {
        EmbeddedInspector.start(freePort());
        EmbeddedInspector.stopWhenHostEnds(() -> true, Duration.ofMillis(20));
        EmbeddedInspector.stop();
        assertThat(EmbeddedInspector.isWatching()).isFalse();

        EmbeddedInspector.start(freePort());
        EmbeddedInspector.stopWhenHostEnds(() -> false, Duration.ofMillis(20));

        await().atMost(Duration.ofSeconds(5)).until(() -> !EmbeddedInspector.isRunning());
    }

    @Test
    void finishesStartingWhenInterrupted() throws Exception {
        int port = freePort();
        Thread.currentThread().interrupt();
        boolean started;
        try {
            started = EmbeddedInspector.start(port);
        } finally {
            assertThat(Thread.interrupted()).as("the interrupt is kept").isTrue();
        }

        assertThat(started).isTrue();
        assertThat(EmbeddedInspector.isRunning()).isTrue();
        assertThat(new InspectorConnection("http://localhost:" + port).ping().state())
                .isEqualTo(InspectorConnection.State.INSPECTOR);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
