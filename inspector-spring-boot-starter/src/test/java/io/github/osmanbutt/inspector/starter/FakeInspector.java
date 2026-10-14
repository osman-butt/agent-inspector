package io.github.osmanbutt.inspector.starter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Answers the ping like the inspector does (or like some other service) and records what is posted to it. */
class FakeInspector implements AutoCloseable {

    final List<String> posted = new CopyOnWriteArrayList<>();
    /** Route names another app registered with another upstream: registering them answers 409. */
    final Set<String> taken = ConcurrentHashMap.newKeySet();
    private final HttpServer server;

    FakeInspector(String pingBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ping", exchange -> respond(exchange, 200, pingBody));
        server.createContext("/api/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            posted.add(exchange.getRequestURI().getPath() + " " + body);
            boolean isTaken = taken.stream().anyMatch(route -> body.contains("\"route\":\"" + route + "\""));
            respond(exchange, isTaken ? 409 : 200, "{}");
        });
        server.start();
    }

    static FakeInspector inspector() throws IOException {
        return new FakeInspector("{\"name\":\"agent-inspector\",\"api\":1}");
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
