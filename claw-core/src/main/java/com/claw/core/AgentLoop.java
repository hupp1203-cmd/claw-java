package com.claw.core;

import com.claw.core.model.Conversation;
import com.claw.core.model.Message;
import com.claw.core.model.ToolCall;
import com.claw.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The core agent turn loop — the heart of the Claw engine.
 *
 * <p>This is the Java port of Claude Code's internal {@code query.ts}.
 * It implements the fundamental agent pattern:
 *
 * <ol>
 *   <li>Send the conversation (with system prompt) to the model provider.</li>
 *   <li>Parse the response: if it contains text, capture it; if it contains
 *       tool calls, execute them.</li>
 *   <li>Append results to the conversation and loop back to step 1.</li>
 *   <li>Stop when the model produces a text-only response (no tool calls)
 *       or the maximum tool round limit is reached.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var loop = new AgentLoop(config);
 * String finalAnswer = loop.run(
 *     conversation,
 *     messages -> apiClient.complete(messages),
 *     toolCall -> toolRegistry.execute(toolCall)
 * );
 * }</pre>
 *
 * @see QueryEngine
 * @see AgentConfig
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentConfig config;
    private final ObjectMapper json;

    /** Creates a new agent loop with the given configuration. */
    public AgentLoop(AgentConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.json = new ObjectMapper();
    }

    // --- Public API ---

    /**
     * Runs the agent loop to completion.
     *
     * @param conversation the conversation state (mutated in place)
     * @param provider     callback that sends messages to the model and returns the raw JSON response
     * @param toolExecutor callback that executes a {@link ToolCall} and returns the result
     * @return the final text response from the model (accumulated across rounds)
     * @throws AgentLoopException if the loop exceeds max rounds or encounters an unrecoverable error
     */
    public String run(Conversation conversation, ProviderCallback provider, ToolExecutor toolExecutor)
            throws AgentLoopException {

        Objects.requireNonNull(conversation, "conversation must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");

        var responseBuilder = new StringBuilder();

        for (int round = 0; round < config.maxToolRounds(); round++) {
            log.debug("Agent loop round {}/{}", round + 1, config.maxToolRounds());

            // 1. Build messages and call provider
            List<Message> messages = conversation.buildModelMessages();
            String rawResponse;
            try {
                rawResponse = provider.complete(messages);
            } catch (Exception e) {
                throw new AgentLoopException("Provider call failed in round " + (round + 1), e);
            }

            if (rawResponse == null || rawResponse.isBlank()) {
                log.warn("Provider returned empty response in round {}", round + 1);
                break;
            }

            // 2. Parse the response
            ParsedResponse parsed = parseResponse(rawResponse);

            // 3. Accumulate text content
            if (parsed.text != null && !parsed.text.isBlank()) {
                responseBuilder.append(parsed.text);
            }

            // 4. If no tool calls, we're done
            if (parsed.toolCalls.isEmpty()) {
                conversation.addMessage(Message.assistant(parsed.text != null ? parsed.text : ""));
                log.debug("Loop complete: no tool calls in round {}", round + 1);
                break;
            }

            // 5. Add assistant message with tool calls
            conversation.addMessage(new Message(Message.Role.ASSISTANT,
                    parsed.text != null ? parsed.text : "", parsed.toolCalls));

            // 6. Execute each tool call and append results
            for (ToolCall tc : parsed.toolCalls) {
                log.debug("Executing tool: {} (id={})", tc.name(), tc.id());
                ToolResult result;
                try {
                    result = toolExecutor.execute(tc);
                } catch (Exception e) {
                    log.error("Tool execution failed: {} - {}", tc.name(), e.getMessage());
                    result = ToolResult.error(tc.id(),
                            "Tool execution error: " + e.getMessage());
                }
                conversation.addMessage(Message.tool(result.toolCallId(), result.content()));
            }

            // 7. If this was the last allowed round, warn
            if (round == config.maxToolRounds() - 1) {
                log.warn("Reached maximum tool rounds ({})", config.maxToolRounds());
                conversation.addMessage(Message.system(
                        "Maximum tool rounds reached. Provide your final answer now."));
            }
        }

        return responseBuilder.toString();
    }

    // --- Response parsing ---

    /**
     * Parses the raw provider response (JSON string) into text content
     * and tool calls.
     *
     * <p>Supports multiple response formats:
     * <ul>
     *   <li>OpenAI-style: {@code {"choices":[{"message":{"content":"...","tool_calls":[...]}}]}}</li>
     *   <li>Anthropic-style: {@code {"content":[{"type":"text","text":"..."}],...}}</li>
     *   <li>Simplified: {@code {"content":"...","tool_calls":[...]}}</li>
     * </ul>
     */
    ParsedResponse parseResponse(String rawJson) {
        try {
            JsonNode root = json.readTree(rawJson);

            String text = null;
            List<ToolCall> toolCalls = new ArrayList<>();

            // Try OpenAI format: choices[0].message.content
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    // Text content
                    JsonNode contentNode = message.get("content");
                    if (contentNode != null && !contentNode.isNull()) {
                        text = contentNode.asText();
                    }
                    // Tool calls
                    JsonNode tcNode = message.get("tool_calls");
                    if (tcNode != null && tcNode.isArray()) {
                        for (JsonNode tc : tcNode) {
                            parseOpenAIToolCall(tc).ifPresent(toolCalls::add);
                        }
                    }
                }
            }

            // Try top-level "content" field (string or content-block array)
            if (text == null) {
                JsonNode contentNode = root.get("content");
                if (contentNode != null) {
                    if (contentNode.isTextual()) {
                        text = contentNode.asText();
                    } else if (contentNode.isArray()) {
                        // Anthropic-style content blocks
                        var sb = new StringBuilder();
                        for (JsonNode block : contentNode) {
                            if ("text".equals(block.path("type").asText())) {
                                sb.append(block.path("text").asText());
                            }
                        }
                        text = sb.toString();
                    }
                }
            }

            // Try top-level "tool_calls"
            JsonNode tcNode = root.get("tool_calls");
            if (tcNode != null && tcNode.isArray()) {
                for (JsonNode tc : tcNode) {
                    parseOpenAIToolCall(tc).ifPresent(toolCalls::add);
                }
            }

            // Try Anthropic-style "tool_use" in content blocks
            JsonNode contentArray = root.get("content");
            if (contentArray != null && contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        String id = block.path("id").asText();
                        String name = block.path("name").asText();
                        JsonNode input = block.path("input");
                        Map<String, Object> args = input.isObject()
                                ? json.convertValue(input, Map.class)
                                : Map.of();
                        toolCalls.add(new ToolCall(id, name, args));
                    }
                }
            }

            return new ParsedResponse(text, Collections.unmodifiableList(toolCalls));

        } catch (Exception e) {
            log.error("Failed to parse provider response: {}", e.getMessage());
            log.debug("Raw response (first 500 chars): {}",
                    rawJson.substring(0, Math.min(500, rawJson.length())));
            // Fallback: treat entire response as text
            return new ParsedResponse(rawJson, List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<ToolCall> parseOpenAIToolCall(JsonNode node) {
        try {
            String id = node.path("id").asText();
            JsonNode function = node.get("function");
            if (function == null) return java.util.Optional.empty();
            String name = function.path("name").asText();
            String argsStr = function.path("arguments").asText();
            Map<String, Object> args;
            try {
                args = argsStr != null && !argsStr.isBlank()
                        ? json.readValue(argsStr, Map.class)
                        : Map.of();
            } catch (Exception e) {
                args = Map.of();
            }
            return java.util.Optional.of(new ToolCall(id, name, args));
        } catch (Exception e) {
            log.warn("Failed to parse tool call node: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    // --- Inner types ---

    /**
     * Parsed result from a provider response.
     *
     * @param text      the text content (may be null if only tool calls)
     * @param toolCalls any tool calls requested in the response
     */
    record ParsedResponse(String text, List<ToolCall> toolCalls) {}

    /**
     * Callback for sending messages to the model provider.
     *
     * <p>Implementations should serialize the messages into the
     * provider's API format, make the HTTP request, and return the
     * raw JSON response body.</p>
     */
    @FunctionalInterface
    public interface ProviderCallback {
        /**
         * Sends a list of messages to the model and returns the raw response.
         *
         * @param messages the conversation messages to send
         * @return the raw JSON response string from the model API
         * @throws Exception on network or API errors
         */
        String complete(List<Message> messages) throws Exception;
    }

    /**
     * Callback for executing a tool call and returning the result.
     *
     * <p>Implementations should look up the tool by name, deserialize
     * the arguments, and invoke the tool. Errors should be reported via
     * {@link ToolResult#isError()} rather than thrown.</p>
     */
    @FunctionalInterface
    public interface ToolExecutor {
        /**
         * Executes a tool call.
         *
         * @param call the tool call to execute
         * @return the result of execution
         * @throws Exception if the tool cannot be executed at all
         */
        ToolResult execute(ToolCall call) throws Exception;
    }

    /**
     * Exception thrown when the agent loop encounters an unrecoverable error.
     */
    public static class AgentLoopException extends RuntimeException {
        public AgentLoopException(String message) {
            super(message);
        }

        public AgentLoopException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
