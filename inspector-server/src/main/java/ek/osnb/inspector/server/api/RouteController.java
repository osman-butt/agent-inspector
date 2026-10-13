package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.proxy.RouteRegistry;
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

    public record RouteRequest(String route, URI upstream) {
    }

    private final RouteRegistry routes;

    public RouteController(RouteRegistry routes) {
        this.routes = routes;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("name", SIGNATURE, "api", 1);
    }

    @GetMapping("/routes")
    public Map<String, URI> list() {
        return routes.all();
    }

    @PostMapping("/routes")
    public Map<String, String> register(@RequestBody RouteRequest request) {
        try {
            routes.register(request.route(), request.upstream());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return Map.of("route", request.route(), "proxy", "/proxy/" + request.route());
    }
}
