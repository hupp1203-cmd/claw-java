package com.claw.core.model;

import java.util.Objects;

/**
 * The result of executing a tool call.
 *
 * <p>Returned by the {@link com.claw.core.AgentLoop.ToolExecutor ToolExecutor}
 * and appended to the conversation as a tool-role message.</p>
 *
 * @param toolCallId the ID of the {@link ToolCall} this result answers
 * @param content    the tool's output as a string (even if the tool returns
 *                   structured data, it should be serialized here)
 * @param isError    {@code true} if the tool invocation failed
 */
public record ToolResult(
        String toolCallId,
        String content,
        boolean isError) {

    public ToolResult {
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    /** Convenience factory for a successful tool result. */
    public static ToolResult success(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, false);
    }

    /** Convenience factory for a failed tool result. */
    public static ToolResult error(String toolCallId, String errorMessage) {
        return new ToolResult(toolCallId, errorMessage, true);
    }
}
