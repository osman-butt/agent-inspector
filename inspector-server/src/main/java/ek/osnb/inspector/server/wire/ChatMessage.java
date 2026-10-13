package ek.osnb.inspector.server.wire;

import java.util.List;

/**
 * @param role {@code system}, {@code user}, {@code assistant} or {@code tool}
 */
public record ChatMessage(String role, List<Part> parts) {
}
