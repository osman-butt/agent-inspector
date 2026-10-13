package io.github.osmanbutt.inspector.server.wire;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/** Null-tolerant helpers for reading provider JSON, which varies between providers and versions. */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** The parsed body, or a missing node when there is none or it isn't JSON. */
    static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return MAPPER.readTree(body);
        } catch (RuntimeException e) {
            return MissingNode.getInstance();
        }
    }

    static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    /** A string as-is, anything else (an object, an array) as compact JSON. */
    static String textOrJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isString() ? node.stringValue() : node.toString();
    }

    static Integer integer(JsonNode node) {
        return node != null && node.isNumber() ? node.intValue() : null;
    }

    static boolean bool(JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }
}
