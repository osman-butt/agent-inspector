package io.github.osmanbutt.inspector.server.wire;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * @param role {@code system}, {@code user}, {@code assistant} or {@code tool}
 * @param raw  the message exactly as it was on the wire; {@code null} for an answer rebuilt
 *             from a stream, which never existed as one JSON object
 */
public record ChatMessage(String role, List<Part> parts, JsonNode raw) {

    public ChatMessage(String role, List<Part> parts) {
        this(role, parts, null);
    }
}
