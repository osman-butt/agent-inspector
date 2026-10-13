package io.github.osmanbutt.inspector.server.wire;

import io.github.osmanbutt.inspector.server.wire.SseFrames.Frame;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SseFramesTest {

    @Test
    void splitsEventsOnBlankLines() {
        assertThat(SseFrames.parse("data: {\"a\":1}\n\ndata: [DONE]\n\n"))
                .containsExactly(new Frame(null, "{\"a\":1}"), new Frame(null, "[DONE]"));
    }

    @Test
    void readsEventNamesAndJoinsDataLines() {
        assertThat(SseFrames.parse("event: message_start\ndata: line 1\ndata: line 2\n\n"))
                .containsExactly(new Frame("message_start", "line 1\nline 2"));
    }

    @Test
    void ignoresCommentsAndOtherFieldsAndHandlesCrlf() {
        assertThat(SseFrames.parse(": ping\r\nid: 7\r\nretry: 100\r\ndata:x\r\n\r\n"))
                .containsExactly(new Frame(null, "x"));
    }

    @Test
    void keepsALastEventWithoutTrailingBlankLine() {
        assertThat(SseFrames.parse("data: one\n\ndata: two")).extracting(Frame::data).containsExactly("one", "two");
    }
}
