package com.claw.provider;

import com.claw.core.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A sealed response from a model provider. Two variants exist:
 * <ul>
 *   <li>{@link TextResponse} — a plain text completion</li>
 *   <li>{@link ToolCallResponse} — one or more tool calls requested by the model</li>
 * </ul>
 */
public sealed interface ProviderResponse {

    ObjectMapper MAPPER = new ObjectMapper();

    /** A text-only completion. */
    record TextResponse(String content) implements ProviderResponse {}

    /** A tool-call-only completion, using the canonical {@link ToolCall} from claw-core. */
    record ToolCallResponse(List<ToolCall> toolCalls) implements ProviderResponse {}

    /**
     * Parse a raw JSON response body into a {@link ProviderResponse}.
     * <p>
     * This method attempts to detect the provider format automatically:
     * <ul>
     *   <li><b>Anthropic</b> — {@code content} array with {@code type: "text"} or {@code "tool_use"}</li>
     *   <li><b>OpenAI / DeepSeek</b> — {@code choices[0].message}</li>
     * </ul>
     *
     * @param json the raw response JSON as a string
     * @return a parsed response
     */
    static ProviderResponse parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);

            // Anthropic format: { "content": [...] }
            if (root.has("content") && root.get("content").isArray()) {
                return parseAnthropic(root.get("content"));
            }

            // OpenAI/DeepSeek format: { "choices": [...] }
            if (root.has("choices") && root.get("choices").isArray()) {
                JsonNode msg = root.get("choices").get(0).get("message");
                if (msg != null) {
                    return parseOpenAi(msg);
                }
            }

            // Fallback: treat as text
            return new TextResponse(root.toString());
        } catch (Exception e) {
            return new TextResponse(json);
        }
    }

    private static ProviderResponse parseAnthropic(JsonNode content) {
        List<ToolCall> toolCalls = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        for (JsonNode block : content) {
            String type = block.has("type") ? block.get("type").asText() : "";
            if ("text".equals(type) && block.has("text")) {
                if (!text.isEmpty()) text.append("\n");
                text.append(block.get("text").asText());
            } else if ("tool_use".equals(type)) {
                String id = block.has("id") ? block.get("id").asText() : "";
                String name = block.has("name") ? block.get("name").asText() : "";
                @SuppressWarnings("unchecked")
                Map<String, Object> input = block.has("input")
                        ? MAPPER.convertValue(block.get("input"), Map.class)
                        : Collections.emptyMap();
                toolCalls.add(new ToolCall(id, name, input));
            }
        }

        if (!toolCalls.isEmpty()) {
            return new ToolCallResponse(Collections.unmodifiableList(toolCalls));
        }
        return new TextResponse(text.toString());
    }

    private static ProviderResponse parseOpenAi(JsonNode message) {
        // Check for tool_calls
        if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode tc : message.get("tool_calls")) {
                String id = tc.has("id") ? tc.get("id").asText() : "";
                JsonNode fn = tc.get("function");
                String name = fn != null && fn.has("name") ? fn.get("name").asText() : "";
                Map<String, Object> arguments = Collections.emptyMap();
                if (fn != null && fn.has("arguments")) {
                    String argsStr = fn.get("arguments").asText();
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = MAPPER.readValue(argsStr, Map.class);
                        arguments = parsed;
                    } catch (Exception ignored) {
                        // leave as empty map
                    }
                }
                toolCalls.add(new ToolCall(id, name, arguments));
            }
            return new ToolCallResponse(Collections.unmodifiableList(toolCalls));
        }

        // Plain text
        String content = message.has("content")
                ? message.get("content").asText("")
                : "";
        return new TextResponse(content);
    }
}
