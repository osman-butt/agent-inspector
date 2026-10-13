package io.github.osmanbutt.inspector.server.proxy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** A stand-in model API: echoes requests at {@code /base/echo} and streams SSE at {@code /base/stream}. */
class FakeUpstream {

    static final String STREAM = "data: one\n\ndata: two\n\ndata: [DONE]\n\n";

    /** Headers of the last request received, as the upstream saw them. */
    static volatile Headers lastHeaders = new Headers();

    private final HttpServer server;

    private FakeUpstream(HttpServer server) {
        this.server = server;
    }

    static FakeUpstream start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/base/echo", FakeUpstream::echo);
            server.createContext("/base/stream", FakeUpstream::stream);
            server.start();
            return new FakeUpstream(server);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/base";
    }

    private static void echo(HttpExchange exchange) throws IOException {
        lastHeaders = exchange.getRequestHeaders();
        String echo = "method=" + exchange.getRequestMethod()
                + "\npath=" + exchange.getRequestURI().getPath()
                + "\nquery=" + exchange.getRequestURI().getQuery()
                + "\nauthorization=" + exchange.getRequestHeaders().getFirst("Authorization")
                + "\nbody=" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] bytes = echo.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.getResponseHeaders().add("X-Upstream", "fake");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void stream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String event : STREAM.split("(?<=\n\n)")) {
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }
}
