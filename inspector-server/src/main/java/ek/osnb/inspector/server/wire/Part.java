package ek.osnb.inspector.server.wire;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** One piece of a message. Serialized with a {@code type} property so the UI can tell them apart. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Part.Text.class, name = "text"),
        @JsonSubTypes.Type(value = Part.Thinking.class, name = "thinking"),
        @JsonSubTypes.Type(value = Part.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = Part.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = Part.Other.class, name = "other")})
public sealed interface Part {

    record Text(String text) implements Part {
    }

    /** Reasoning the model shows before answering. */
    record Thinking(String text) implements Part {
    }

    /** The model asks the app to run a tool. {@code arguments} is the JSON exactly as the model wrote it. */
    record ToolCall(String id, String name, String arguments) implements Part {
    }

    /** The app sends a tool's result back, matched to the call by {@code toolCallId}. */
    record ToolResult(String toolCallId, String content, boolean error) implements Part {
    }

    /** Content the inspector doesn't show inline, such as images or files. */
    record Other(String label) implements Part {
    }
}
