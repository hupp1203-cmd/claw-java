package com.claw.core;

import com.claw.core.model.Conversation;
import com.claw.core.model.Message;
import com.claw.core.model.ToolCall;
import com.claw.core.model.ToolResult;
import com.claw.core.permission.PermissionLevel;
import com.claw.core.permission.PermissionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The core agent turn loop — the heart of the Claw engine.
 *
 * <p>This is the Java port of Claude Code's internal {@code query.ts}.
 * It implements the fundamental agent pattern:
 *
 * <ol>
 *   <li>Send the conversation (with system prompt) to the model provider.</li>
 *   <li>Parse the typed response: if it contains text, stream it and accumulate;
 *       if it contains tool calls, execute them.</li>
 *   <li>Append results to the conversation and loop back to step 1.</li>
 *   <li>Stop when the model produces a text-only response (no tool calls)
 *       or the maximum tool round limit is reached.</li>
 * </ol>
 *
 * <h3>Streaming</h3>
 * The {@link ProviderCallback} receives a {@link Consumer}{@code <String>} token
 * callback. Providers that support streaming deliver tokens in real-time; the
 * REPL can render them as they arrive for a responsive user experience.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var loop = new AgentLoop(config);
 * String finalAnswer = loop.run(
 *     conversation,
 *     (messages, onToken) -> provider.completeStreaming(...),
 *     toolCall -> toolRegistry.execute(toolCall),
 *     token -> terminal.print(token)   // optional streaming callback
 * );
 * }</pre>
 *
 * @see QueryEngine
 * @see AgentConfig
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentConfig config;
    private final PermissionManager permissions;

    /** Creates a new agent loop with the given configuration. */
    public AgentLoop(AgentConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.permissions = new PermissionManager(PermissionLevel.ALLOW_ALL);
    }

    /** Creates a new agent loop with custom permission level. */
    public AgentLoop(AgentConfig config, PermissionLevel permissionLevel) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.permissions = new PermissionManager(permissionLevel);
    }

    // --- Public API ---

    /**
     * Runs the agent loop to completion.
     *
     * @param conversation the conversation state (mutated in place)
     * @param provider     callback that sends messages to the model and returns a typed response;
     *                     receives an {@code onToken} consumer for streaming text delivery
     * @param toolExecutor callback that executes a {@link ToolCall} and returns the result
     * @param onToken      consumer for streaming text tokens (may be {@code null} for non-streaming)
     * @return the final text response from the model (accumulated across rounds)
     * @throws AgentLoopException if the loop exceeds max rounds or encounters an unrecoverable error
     */
    public String run(Conversation conversation, ProviderCallback provider,
                       ToolExecutor toolExecutor, Consumer<String> onToken)
            throws AgentLoopException {

        Objects.requireNonNull(conversation, "conversation must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");

        var responseBuilder = new StringBuilder();

        for (int round = 0; round < config.maxToolRounds(); round++) {
            log.debug("Agent loop round {}/{}", round + 1, config.maxToolRounds());

            // 1. Build messages and call provider
            List<Message> messages = conversation.buildModelMessages();
            LoopResponse response;
            try {
                response = provider.complete(messages, onToken);
            } catch (Exception e) {
                throw new AgentLoopException("Provider call failed in round " + (round + 1), e);
            }

            // 2. Handle based on response type
            switch (response) {
                case LoopResponse.Text(var text) -> {
                    // No tool calls — we're done
                    responseBuilder.append(text);
                    conversation.addMessage(Message.assistant(text));
                    log.debug("Loop complete: text response in round {}", round + 1);
                    return responseBuilder.toString();
                }
                case LoopResponse.ToolUse(var text, var toolCalls) -> {
                    // Accumulate any text accompanying the tool calls
                    if (text != null && !text.isBlank()) {
                        responseBuilder.append(text);
                    }

                    if (toolCalls.isEmpty()) {
                        // Edge case: text-only but wrapped in ToolUse
                        conversation.addMessage(Message.assistant(text != null ? text : ""));
                        return responseBuilder.toString();
                    }

                    // Add assistant message with tool calls
                    conversation.addMessage(new Message(Message.Role.ASSISTANT,
                            text != null ? text : "", toolCalls));

                    // Execute each tool call and append results
                    for (ToolCall tc : toolCalls) {
                        log.debug("Executing tool: {} (id={})", tc.name(), tc.id());
                        ToolResult result;

                        // Check permissions
                        if (!permissions.isAllowed(tc.name())) {
                            result = ToolResult.error(tc.id(),
                                    "Tool execution denied: permission level is NONE");
                        } else if (permissions.shouldAsk(tc.name())) {
                            result = ToolResult.error(tc.id(),
                                    "Tool execution requires user confirmation: " + tc.name()
                                    + " (permission level is ASK)");
                        } else {
                            try {
                                result = toolExecutor.execute(tc);
                            } catch (Exception e) {
                                log.error("Tool execution failed: {} - {}", tc.name(), e.getMessage());
                                result = ToolResult.error(tc.id(),
                                        "Tool execution error: " + e.getMessage());
                            }
                        }
                        conversation.addMessage(Message.tool(result.toolCallId(), result.content()));
                    }
                }
            }

            // If this was the last allowed round, warn
            if (round == config.maxToolRounds() - 1) {
                log.warn("Reached maximum tool rounds ({})", config.maxToolRounds());
                conversation.addMessage(Message.system(
                        "Maximum tool rounds reached. Provide your final answer now."));
            }
        }

        return responseBuilder.toString();
    }

    /**
     * Convenience overload without streaming callback.
     */
    public String run(Conversation conversation, ProviderCallback provider,
                       ToolExecutor toolExecutor) throws AgentLoopException {
        return run(conversation, provider, toolExecutor, null);
    }

    // --- Inner types ---

    /**
     * Typed response from a provider call — no JSON round-trip needed.
     *
     * <p>Two variants:
     * <ul>
     *   <li>{@link Text} — a final text response with no tool calls</li>
     *   <li>{@link ToolUse} — text content plus one or more tool invocation requests</li>
     * </ul>
     */
    public sealed interface LoopResponse {
        /** A plain text response — the agent is finished. */
        record Text(String content) implements LoopResponse {}

        /** Text content accompanied by tool calls. Text may be empty if purely tool calls. */
        record ToolUse(String text, java.util.List<ToolCall> toolCalls) implements LoopResponse {}
    }

    /**
     * Callback for sending messages to the model provider.
     *
     * <p>Implementations should serialize the messages into the
     * provider's API format, make the HTTP request, and return a
     * typed {@link LoopResponse}. The {@code onToken} callback
     * receives streaming text tokens as they arrive (may be null
     * if the caller doesn't need streaming).</p>
     */
    @FunctionalInterface
    public interface ProviderCallback {
        /**
         * Sends a list of messages to the model.
         *
         * @param messages the conversation messages to send
         * @param onToken  consumer for streaming text tokens (may be {@code null})
         * @return a typed response with text and/or tool calls
         * @throws Exception on network or API errors
         */
        LoopResponse complete(List<Message> messages, Consumer<String> onToken) throws Exception;
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
