package io.github.osmanbutt.inspector.server.proxy;

import io.github.osmanbutt.inspector.server.InspectorProperties;
import io.github.osmanbutt.inspector.server.capture.Exchange;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Recording reverse proxy. An application points its model base URL at
 * {@code /proxy/<route>}; every call is forwarded to the route's upstream, the response is
 * streamed back unchanged, and both directions are stored as an {@link Exchange}.
 */
@RestController
public class ProxyController {

    // Hop-by-hop headers, and headers the JDK HttpClient sets itself.
    // accept-encoding is dropped so the upstream answers uncompressed and the copy stays readable.
    private static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of(
            "host", "connection", "content-length", "expect", "upgrade", "keep-alive", "te", "trailer",
            "transfer-encoding", "proxy-connection", "http2-settings", "accept-encoding");

    private static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of(
            "connection", "content-length", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade");

    /** Headers meant for the inspector itself: recorded, never forwarded. */
    public static final String SESSION_HEADER = "x-inspector-session";

    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "proxy-authorization", "x-api-key", "api-key", "x-goog-api-key");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final InspectorProperties properties;
    private final RouteRegistry routes;
    private final ExchangeStore store;

    public ProxyController(InspectorProperties properties, RouteRegistry routes, ExchangeStore store) {
        this.properties = properties;
        this.routes = routes;
        this.store = store;
    }

    @RequestMapping("/proxy/{route}/**")
    public void proxy(@PathVariable String route, HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        URI upstream = routes.upstream(route).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Unknown route '" + route + "'. Configure agent.inspector.routes." + route));

        String path = request.getRequestURI().substring((request.getContextPath() + "/proxy/" + route).length());
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        String url = upstream.toString().replaceAll("/+$", "") + path + query;
        byte[] body = request.getInputStream().readAllBytes();
        long maxBody = properties.maxBodySize().toBytes();

        HttpRequest.Builder forward = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .method(request.getMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (SKIPPED_REQUEST_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            if (name.equalsIgnoreCase(SESSION_HEADER)) {
                requestHeaders.put(SESSION_HEADER, request.getHeader(name));
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                forward.header(name, value);
            }
            requestHeaders.put(name.toLowerCase(), redact(name, String.join(", ", Collections.list(request.getHeaders(name)))));
        }

        Exchange exchange = Exchange.start(route, new Exchange.Request(
                request.getMethod(), url, path, requestHeaders, BodyCopy.of(body, maxBody).toText(null)));
        store.save(exchange);
        long start = System.nanoTime();

        HttpResponse<InputStream> upstreamResponse;
        try {
            upstreamResponse = httpClient.send(forward.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String error = "Could not reach " + upstream + ": " + e;
            store.save(exchange.complete(new Exchange.Response(502, Map.of(), null, millisSince(start), error)));
            response.sendError(HttpStatus.BAD_GATEWAY.value(), error);
            return;
        }

        response.setStatus(upstreamResponse.statusCode());
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        upstreamResponse.headers().map().forEach((name, values) -> {
            if (name.startsWith(":") || SKIPPED_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                return;
            }
            values.forEach(value -> response.addHeader(name, value));
            responseHeaders.put(name.toLowerCase(), String.join(", ", values));
        });

        // Pass each chunk on as soon as it arrives, so streamed (SSE) answers stay live,
        // and keep a copy. The exchange is completed even when the stream breaks off.
        BodyCopy copy = new BodyCopy(maxBody);
        String error = null;
        try (InputStream in = upstreamResponse.body()) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
                copy.write(buffer, 0, read);
            }
        } catch (IOException e) {
            error = "Stream interrupted: " + e;
            throw e;
        } finally {
            String contentEncoding = upstreamResponse.headers().firstValue("content-encoding").orElse(null);
            store.save(exchange.complete(new Exchange.Response(upstreamResponse.statusCode(), responseHeaders,
                    copy.toText(contentEncoding), millisSince(start), error)));
        }
    }

    /** Keeps a short prefix, enough to tell keys apart, and hides the rest. */
    static String redact(String name, String value) {
        if (!SECRET_HEADERS.contains(name.toLowerCase())) {
            return value;
        }
        return value.substring(0, Math.min(10, value.length() / 3)) + "…(redacted)";
    }

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
