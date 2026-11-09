package io.github.osmanbutt.inspector.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Refuses requests addressed to a host name that a web page could control. Listening on localhost
 * does not keep web pages out: with DNS rebinding a page's own domain starts resolving to
 * 127.0.0.1, and the browser then lets the page read the captured traffic and use the proxy. Those
 * requests still name the page's domain in their {@code Host} header.
 *
 * <p>Allowed are {@code localhost}, IP addresses, names without dots (like a Docker Compose service
 * name) and the names in {@code agent.inspector.allowed-hosts}.
 */
@Component
class HostCheckFilter extends OncePerRequestFilter {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final Set<String> allowedHosts;

    HostCheckFilter(InspectorProperties properties) {
        this.allowedHosts = properties.allowedHosts().stream()
                .map(HostCheckFilter::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Host");
        String host = hostname(StringUtils.hasText(header) ? header : request.getServerName());
        if (allowed(host)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getWriter().write("Agent Inspector refuses requests for host " + host
                + ". To allow it, add it to agent.inspector.allowed-hosts.\n");
    }

    boolean allowed(String host) {
        return host.equals("localhost") || host.endsWith(".localhost")
                || host.startsWith("[") || host.contains(":") // IPv6, with or without brackets
                || IPV4.matcher(host).matches()
                || !host.contains(".")
                || allowedHosts.contains(host);
    }

    /** The host without its port, lowercase and without a trailing dot. */
    static String hostname(String host) {
        String name = host.strip();
        if (name.startsWith("[")) {
            int end = name.indexOf(']');
            name = end < 0 ? name : name.substring(0, end + 1);
        } else if (name.indexOf(':') == name.lastIndexOf(':') && name.indexOf(':') >= 0) {
            name = name.substring(0, name.indexOf(':')); // one colon: host:port
        }
        return normalize(name);
    }

    private static String normalize(String host) {
        String name = host.strip().toLowerCase(Locale.ROOT);
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
