package io.github.osmanbutt.inspector.server.api;

import io.github.osmanbutt.inspector.server.proxy.RouteRegistry;
import io.github.osmanbutt.inspector.server.proxy.RouteTakenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;

/** Used by the starter: find the inspector, then register where an app's model traffic should go. */
@RestController
@RequestMapping("/api")
public class RouteController {

    /** What the starter checks for, so another service on the same port is never mistaken for the inspector. */
    public static final String SIGNATURE = "agent-inspector";

    /**
     * @param app the application registering the route, so its ChatClient reports can be matched to its traffic
     */
    public record RouteRequest(String route, URI upstream, String app) {
    }

    private final RouteRegistry routes;
    private final boolean embedded;

    /**
     * @param embedded true when the starter started this inspector inside an application
     */
    public RouteController(RouteRegistry routes, @Value("${agent.inspector.embedded:false}") boolean embedded) {
        this.routes = routes;
        this.embedded = embedded;
    }

    /**
     * Identifies the inspector. An app only routes its traffic to an inspector embedded in another
     * app's process if it has to, since stopping that app would take the inspector down with it.
     */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("name", SIGNATURE, "api", 1, "embedded", embedded, "pid", ProcessHandle.current().pid());
    }

    @GetMapping("/routes")
    public Map<String, URI> list() {
        return routes.all();
    }

    @PostMapping("/routes")
    public Map<String, String> register(@RequestBody RouteRequest request) {
        try {
            routes.register(request.route(), request.upstream(), request.app());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RouteTakenException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return Map.of("route", request.route(), "proxy", "/proxy/" + request.route());
    }
}
