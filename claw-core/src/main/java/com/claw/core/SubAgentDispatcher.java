package com.claw.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Dispatches multiple {@link SubAgent}s in parallel using virtual threads,
 * waits for all to complete, and merges their results.
 *
 * <p>Usage:
 * <pre>{@code
 * var results = new SubAgentDispatcher(config, provider, toolExecutor)
 *     .dispatch(List.of(
 *         new SubAgent("search-1", "搜索 A 模块的实现", ...),
 *         new SubAgent("search-2", "搜索 B 模块的依赖", ...)
 *     ));
 * String merged = SubAgentDispatcher.merge(results);
 * }</pre>
 */
public final class SubAgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SubAgentDispatcher.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final AgentConfig config;
    private final AgentLoop.ProviderCallback provider;
    private final AgentLoop.ToolExecutor toolExecutor;
    private final Duration timeout;

    public SubAgentDispatcher(AgentConfig config,
                               AgentLoop.ProviderCallback provider,
                               AgentLoop.ToolExecutor toolExecutor) {
        this(config, provider, toolExecutor, DEFAULT_TIMEOUT);
    }

    public SubAgentDispatcher(AgentConfig config,
                               AgentLoop.ProviderCallback provider,
                               AgentLoop.ToolExecutor toolExecutor,
                               Duration timeout) {
        this.config = config;
        this.provider = provider;
        this.toolExecutor = toolExecutor;
        this.timeout = timeout;
    }

    /**
     * Runs all sub-agents in parallel and returns their results in order.
     * Uses one virtual thread per sub-agent.
     *
     * @param agents list of sub-agents to run
     * @return results in the same order as the input list
     */
    public List<SubAgent.SubAgentResult> dispatch(List<SubAgent> agents) {
        if (agents.isEmpty()) return List.of();

        log.info("Dispatching {} sub-agents in parallel", agents.size());
        long startMs = System.currentTimeMillis();

        List<SubAgent.SubAgentResult> results = new ArrayList<>(agents.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SubAgent.SubAgentResult>> futures = agents.stream()
                    .map(agent -> executor.submit(agent::run))
                    .toList();

            for (int i = 0; i < futures.size(); i++) {
                Future<SubAgent.SubAgentResult> future = futures.get(i);
                SubAgent agent = agents.get(i);
                try {
                    SubAgent.SubAgentResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    results.add(result);
                    log.debug("[{}] result collected", agent.name());
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    log.warn("[{}] timed out after {}", agent.name(), timeout);
                    results.add(SubAgent.SubAgentResult.failure(
                            agent.name(), agent.task(),
                            "Timed out after " + timeout.toSeconds() + "s"));
                } catch (Exception e) {
                    log.warn("[{}] dispatch error: {}", agent.name(), e.getMessage());
                    results.add(SubAgent.SubAgentResult.failure(
                            agent.name(), agent.task(), e.getMessage()));
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        long succeeded = results.stream().filter(SubAgent.SubAgentResult::success).count();
        log.info("All sub-agents done in {}ms: {}/{} succeeded", elapsed, succeeded, agents.size());

        return results;
    }

    /**
     * Merges a list of sub-agent results into a single string suitable for
     * returning to the parent agent as a tool result.
     *
     * <p>Format:
     * <pre>
     * ## Sub-agent results (3 agents)
     *
     * ### [search-1] 搜索 A 模块
     * ...result text...
     *
     * ### [search-2] 搜索 B 模块
     * ...result text...
     * </pre>
     */
    public static String merge(List<SubAgent.SubAgentResult> results) {
        if (results.isEmpty()) return "No sub-agent results.";

        var sb = new StringBuilder();
        sb.append("## Sub-agent results (").append(results.size()).append(" agents)\n\n");

        for (SubAgent.SubAgentResult r : results) {
            sb.append("### [").append(r.agentName()).append("] ").append(r.task()).append("\n");
            if (r.success()) {
                sb.append(r.result());
            } else {
                sb.append("[FAILED: ").append(r.error()).append("]");
            }
            sb.append("\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * Convenience: build SubAgents from a list of (name, task) pairs and dispatch them.
     */
    public String dispatchAndMerge(List<SubAgentSpec> specs) {
        List<SubAgent> agents = specs.stream()
                .map(s -> new SubAgent(s.name(), s.task(), config, provider, toolExecutor))
                .toList();
        return merge(dispatch(agents));
    }

    /**
     * Specification for a sub-agent — name and task.
     */
    public record SubAgentSpec(String name, String task) {}
}
