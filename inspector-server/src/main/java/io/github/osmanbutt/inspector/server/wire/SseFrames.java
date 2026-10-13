package io.github.osmanbutt.inspector.server.wire;

import java.util.ArrayList;
import java.util.List;

/** Splits a recorded server-sent-events body into its events. */
final class SseFrames {

    /**
     * @param event the {@code event:} name, or {@code null} when the event had none
     * @param data  the {@code data:} lines joined with newlines
     */
    record Frame(String event, String data) {
    }

    private SseFrames() {
    }

    static List<Frame> parse(String body) {
        List<Frame> frames = new ArrayList<>();
        if (body == null) {
            return frames;
        }
        String event = null;
        StringBuilder data = null;
        for (String line : body.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                if (data != null) {
                    frames.add(new Frame(event, data.toString()));
                }
                event = null;
                data = null;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon == 0) {
                continue; // comment
            }
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (field.equals("event")) {
                event = value;
            } else if (field.equals("data")) {
                data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
            }
        }
        if (data != null) {
            frames.add(new Frame(event, data.toString())); // last event without a trailing blank line
        }
        return frames;
    }
}
