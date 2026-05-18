package com.claw.core;

import com.claw.core.model.Conversation;
import com.claw.core.model.Message;
import com.claw.core.model.ToolCall;
import com.claw.core.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * High-level orchestrator that wraps the {@link AgentLoop} with conversation
 * lifecycle management.
 *
 * <p>This is the Java port of Claude Code's {@code QueryEngine.ts}. It
 * maintains a {@link Conversation} across multiple user turns, handles system
 * prompt injection, triggers automatic compaction when the context window
 * grows too large, and provides session save/restore hooks.</p>
 *
 * <h3>Typical Usage</h3>
 * <pre>{@code
 * var engine = new QueryEngine(config, provider, toolExecutor);
 * String reply = engine.chat("What files are in this directory?");
 * String followUp = engine.continue_("Show me the contents of pom.xml");
 * }</pre>
 *
 * @see AgentLoop
 * @see AgentConfig
 * @see Conversation
 */
public class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);

    private final AgentConfig config;
    private final AgentLoop.ProviderCallback provider;
    private final AgentLoop.ToolExecutor toolExecutor;
    private final AgentLoop loop;
    private final Conversation conversation;
    private final String sessionId;
    private boolean systemPromptInjected;

    /**
     * Creates a new query engine.
     *
     * @param config       agent configuration
     * @param provider     callback to the model API
     * @param toolExecutor callback to execute tools
     */
    public QueryEngine(AgentConfig config,
                       AgentLoop.ProviderCallback provider,
                       AgentLoop.ToolExecutor toolExecutor) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.loop = new AgentLoop(config);
        this.conversation = new Conversation();
        this.sessionId = UUID.randomUUID().toString();
        this.systemPromptInjected = false;
    }

    // --- Public API ---

    /**
     * Sends a user message to the agent and returns the final response.
     *
     * <p>On the first call, the system prompt from {@link AgentConfig} is
     * automatically injected into the conversation. Subsequent calls append
     * to the existing conversation history.</p>
     *
     * @param userMessage the user's input text
     * @return the agent's final text response
     * @throws AgentLoop.AgentLoopException if the agent loop fails
     */
    public String chat(String userMessage) {
        Objects.requireNonNull(userMessage, "userMessage must not be null");

        // Inject system prompt on first message if configured
        if (!systemPromptInjected && config.systemPrompt() != null) {
            conversation.setSystemPrompt(config.systemPrompt());
            systemPromptInjected = true;
            log.debug("System prompt injected ({} chars)", config.systemPrompt().length());
        }

        // Check compaction before adding new message
        if (conversation.shouldCompact()) {
            log.info("Compacting conversation ({} chars before compaction)",
                    conversation.totalCharacterCount());
            conversation.compact();
        }

        // Add user message
        conversation.addMessage(Message.user(userMessage));

        // Run the agent loop
        String response = loop.run(conversation, provider, toolExecutor);

        log.debug("chat complete: {} messages, {} chars response",
                conversation.messageCount(), response.length());
        return response;
    }

    /**
     * Continues the conversation from its current state without adding a
     * new user message.
     *
     * <p>Useful for scenarios where the conversation already contains a
     * prompt and the loop should pick up from where it left off (e.g.
     * after a tool round limit was hit, or when resuming an interrupted
     * session).</p>
     *
     * @return the agent's final response
     * @throws AgentLoop.AgentLoopException if the agent loop fails
     */
    public String continue_() {
        if (conversation.messageCount() == 0) {
            log.warn("continue_ called on empty conversation");
            return "";
        }

        if (conversation.shouldCompact()) {
            conversation.compact();
        }

        return loop.run(conversation, provider, toolExecutor);
    }

    /**
     * Continues the conversation with a follow-up user message.
     *
     * <p>This is a convenience alias for {@link #chat(String)} that makes
     * the follow-up semantics explicit.</p>
     *
     * @param userMessage follow-up input
     * @return the agent's response
     */
    public String continue_(String userMessage) {
        return chat(userMessage);
    }

    /**
     * Resumes a conversation from a previously saved session.
     *
     * <p>Currently a placeholder. In a full implementation this would
     * deserialize the conversation state from disk or a database.</p>
     *
     * @param sessionId the session to resume
     * @return a new {@code QueryEngine} primed with the restored state
     * @throws UnsupportedOperationException always (not yet implemented)
     */
    public static QueryEngine resume(String sessionId) {
        throw new UnsupportedOperationException(
                "Session resume is not yet implemented. Session ID: " + sessionId);
    }

    // --- Accessors ---

    /** Returns the current session ID. */
    public String getSessionId() {
        return sessionId;
    }

    /** Returns the underlying conversation (for inspection/serialization). */
    public Conversation getConversation() {
        return conversation;
    }

    /** Returns the agent configuration. */
    public AgentConfig getConfig() {
        return config;
    }

    /** Returns {@code true} when the conversation should be compacted. */
    public boolean needsCompaction() {
        return conversation.shouldCompact();
    }

    /**
     * Manually triggers conversation compaction.
     *
     * @see Conversation#compact()
     */
    public void compact() {
        conversation.compact();
    }

    /** Returns the total number of messages in the conversation. */
    public int messageCount() {
        return conversation.messageCount();
    }

    /** Returns the estimated token count for the conversation. */
    public int estimatedTokens() {
        return conversation.estimateTokens();
    }

    @Override
    public String toString() {
        return "QueryEngine{session=%s, messages=%d, tokens≈%d}"
                .formatted(sessionId, conversation.messageCount(), conversation.estimateTokens());
    }
}
