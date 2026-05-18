package com.claw.provider;

import java.util.List;
import java.util.Map;

/**
 * A request to a model provider.
 *
 * @param model     the model identifier (e.g. "claude-sonnet-4-20250514")
 * @param messages  the conversation messages
 * @param maxTokens maximum tokens to generate
 * @param temperature sampling temperature (0.0–2.0)
 * @param tools     tool definitions available to the model
 */
public record ProviderRequest(
        String model,
        List<Message> messages,
        int maxTokens,
        double temperature,
        List<ToolDef> tools) {

    /** A single message in a conversation. */
    public record Message(
            String role,
            String content,
            String name,
            List<ToolCall> toolCalls,
            String toolCallId) {
    }

    /** A tool call made by the model. */
    public record ToolCall(
            String id,
            String name,
            Map<String, Object> arguments) {
    }

    /** A tool definition (JSON Schema) exposed to the model. */
    public record ToolDef(
            String name,
            String description,
            Map<String, Object> parameters) {
    }
}
