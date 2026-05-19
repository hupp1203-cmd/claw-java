package com.claw.provider;

import com.claw.core.model.Message;

import java.util.List;
import java.util.Map;

/**
 * A request to a model provider.
 *
 * <p>Uses the canonical {@link com.claw.core.model.Message} type directly,
 * eliminating the need for manual type bridging between core and provider layers.</p>
 *
 * @param model       the model identifier (e.g. "claude-sonnet-4-20250514")
 * @param messages    the conversation messages (using core model types)
 * @param maxTokens   maximum tokens to generate
 * @param temperature sampling temperature (0.0–2.0)
 * @param tools       tool definitions available to the model
 */
public record ProviderRequest(
        String model,
        List<Message> messages,
        int maxTokens,
        double temperature,
        List<ToolDef> tools) {

    /** A tool definition (JSON Schema) exposed to the model. */
    public record ToolDef(
            String name,
            String description,
            Map<String, Object> parameters) {
    }
}
