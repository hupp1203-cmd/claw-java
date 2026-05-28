package com.claw.core;

import com.claw.core.model.Conversation;
import com.claw.core.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * A single sub-agent that runs an isolated {@link AgentLoop} with its own
 * {@link Conversation}. Designed to be executed in parallel via
 * {@link SubAgentDispatcher}.
 *
 * <p>Each sub-agent receives a task description, runs it to completion, and
 * returns a plain-text result. The parent agent receives all results merged
 * together and synthesizes the final answer.
 */
public final class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    private final String name;
    private final String task;
    private final AgentConfig config;
    private final AgentLoop.ProviderCallback provider;
    private final AgentLoop.ToolExecutor toolExecutor;

    /**
     * @param name         short label for this sub-agent (e.g. "search-1", "analyze-2")
     * @param task         the task prompt to execute
     * @param config       agent configuration (model, provider, system prompt, etc.)
     * @param provider     provider callback shared from the parent agent
     * @param toolExecutor tool executor shared from the parent agent
     */
    public SubAgent(String name, String task,
                    AgentConfig config,
                    AgentLoop.ProviderCallback provider,
                    AgentLoop.ToolExecutor toolExecutor) {
        this.name = Objects.requireNonNull(name);
        this.task = Objects.requireNonNull(task);
        this.config = Objects.requireNonNull(config);
        this.provider = Objects.requireNonNull(provider);
        this.toolExecutor = Objects.requireNonNull(toolExecutor);
    }

    public String name() { return name; }
    public String task() { return task; }

    /**
     * Runs the sub-agent to completion and returns the result text.
     * Blocks the calling thread until done.
     */
    public SubAgentResult run() {
        log.debug("[{}] starting task: {}", name, task.length() > 80 ? task.substring(0, 80) + "..." : task);
        long startMs = System.currentTimeMillis();

        var conversation = new Conversation();
        if (config.systemPrompt() != null) {
            conversation.setSystemPrompt(config.systemPrompt());
        }
        conversation.addMessage(Message.user(task));

        var loop = new AgentLoop(config);
        try {
            String result = loop.run(conversation, provider, toolExecutor, null);
            long elapsed = System.currentTimeMillis() - startMs;
            log.debug("[{}] completed in {}ms, {} chars", name, elapsed, result.length());
            return SubAgentResult.success(name, task, result);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.warn("[{}] failed after {}ms: {}", name, elapsed, e.getMessage());
            return SubAgentResult.failure(name, task, e.getMessage());
        }
    }

    /**
     * The result of a sub-agent run.
     *
     * @param agentName the sub-agent's name
     * @param task      the original task
     * @param result    the result text (null on failure)
     * @param error     the error message (null on success)
     * @param success   whether the run succeeded
     */
    public record SubAgentResult(
            String agentName,
            String task,
            String result,
            String error,
            boolean success) {

        static SubAgentResult success(String name, String task, String result) {
            return new SubAgentResult(name, task, result, null, true);
        }

        static SubAgentResult failure(String name, String task, String error) {
            return new SubAgentResult(name, task, null, error, false);
        }

        /** Returns result text, or an error marker if failed. */
        public String resultOrError() {
            return success ? result : "[ERROR: " + error + "]";
        }
    }
}
