package io.github.osmanbutt.inspector.server.proxy;

import io.github.osmanbutt.inspector.server.InspectorProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Where each proxy route forwards to: the routes from {@code agent.inspector.routes.*}, plus
 * routes that instrumented applications register at startup (see the starter), so each app's
 * traffic goes to the base URL it was configured with.
 */
@Component
public class RouteRegistry {

    private static final Pattern ROUTE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final Map<String, URI> routes = new ConcurrentHashMap<>();
    private final Map<String, String> apps = new ConcurrentHashMap<>();

    public RouteRegistry(InspectorProperties properties) {
        routes.putAll(properties.routes());
    }

    public Optional<URI> upstream(String route) {
        return Optional.ofNullable(routes.get(route));
    }

    /** The application that registered the route, if it was registered by the starter. */
    public Optional<String> app(String route) {
        return Optional.ofNullable(apps.get(route));
    }

    /**
     * Adds a route. Names are lowercase letters, digits and dashes. Registering the same upstream
     * again is fine (an app restarting); a name that forwards somewhere else is refused, so one
     * app never takes over another's traffic.
     *
     * @throws RouteTakenException when the name already forwards to another upstream
     */
    public void register(String route, URI upstream, String app) {
        if (route == null || !ROUTE_NAME.matcher(route).matches()) {
            throw new IllegalArgumentException("Route names are lowercase letters, digits and dashes: " + route);
        }
        if (upstream == null || upstream.getScheme() == null
                || !(upstream.getScheme().equals("http") || upstream.getScheme().equals("https"))) {
            throw new IllegalArgumentException("Upstream must be an http(s) URL: " + upstream);
        }
        URI existing = routes.putIfAbsent(route, upstream);
        if (existing != null && !existing.equals(upstream)) {
            throw new RouteTakenException("Route " + route + " already forwards to " + existing);
        }
        if (app != null) {
            apps.put(route, app);
        }
    }

    public Map<String, URI> all() {
        return Map.copyOf(routes);
    }
}
