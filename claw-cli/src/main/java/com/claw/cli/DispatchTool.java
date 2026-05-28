package com.claw.cli;

import com.claw.core.AgentConfig;
import com.claw.core.AgentLoop;
import com.claw.core.SubAgentDispatcher;
import com.claw.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool that lets the main agent dispatch parallel sub-agents.
 *
 * <p>The main agent calls this tool with a list of tasks; each task runs in
 * its own isolated {@link com.claw.core.AgentLoop} on a virtual thread.
 * All sub-agents share the same provider and tool executor as the parent.
 * Results are merged and returned as a single string.
 *
 * <p>Example tool call the model generates:
 * <pre>
 * {
 *   "tasks": [
 *     {"name": "search-auth",  "task": "搜索项目里认证相关的代码，列出关键文件和方法"},
 *     {"name": "search-db",    "task": "搜索数据库连接和事务相关的代码"},
 *     {"name": "search-tests", "task": "找出现有测试覆盖了哪些模块"}
 *   ]
 * }
 * </pre>
 */
public final class DispatchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DispatchTool.class);
    private static final int MAX_AGENTS = 10;
    private static final Duration AGENT_TIMEOUT = Duration.ofMinutes(3);

    private final AgentConfig config;
    private final AgentLoop.ProviderCallback provider;
    private final AgentLoop.ToolExecutor toolExecutor;

    public DispatchTool(AgentConfig config,
                        AgentLoop.ProviderCallback provider,
                        AgentLoop.ToolExecutor toolExecutor) {
        this.config = config;
        this.provider = provider;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public String name() {
        return "dispatch_agents";
    }

    @Override
    public String description() {
        return "Dispatch multiple sub-agents to run in parallel. Each sub-agent independently " +
               "executes its task using the same tools as the main agent. Use this when you need " +
               "to search or analyze multiple independent topics simultaneously. " +
               "Results are returned merged when all agents complete.";
    }

    @Override
    public Duration timeout() {
        return Duration.ofMinutes(4);
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "tasks", Map.of(
                    "type", "array",
                    "description", "List of tasks to run in parallel. Max " + MAX_AGENTS + " tasks.",
                    "maxItems", MAX_AGENTS,
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "name", Map.of(
                                "type", "string",
                                "description", "Short label for this sub-agent, e.g. 'search-auth'"
                            ),
                            "task", Map.of(
                                "type", "string",
                                "description", "The task prompt for this sub-agent to execute"
                            )
                        ),
                        "required", List.of("name", "task")
                    )
                )
            ),
            "required", List.of("tasks")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        Object tasksObj = arguments.get("tasks");
        if (!(tasksObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return "Error: 'tasks' must be a non-empty array";
        }

        List<SubAgentDispatcher.SubAgentSpec> specs = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> taskMap)) continue;
            String name = (String) taskMap.get("name");
            String task = (String) taskMap.get("task");
            if (name == null || task == null || name.isBlank() || task.isBlank()) continue;
            specs.add(new SubAgentDispatcher.SubAgentSpec(name, task));
        }

        if (specs.isEmpty()) {
            return "Error: no valid tasks found in input";
        }
        if (specs.size() > MAX_AGENTS) {
            return "Error: too many tasks (" + specs.size() + "), max is " + MAX_AGENTS;
        }

        log.info("dispatch_agents: launching {} sub-agents", specs.size());

        // Sub-agents use a reduced but generous limit to avoid infinite nesting
        AgentConfig subConfig = config.withMaxToolRounds(
                Math.min(config.maxToolRounds(), 20));

        var dispatcher = new SubAgentDispatcher(subConfig, provider, toolExecutor, AGENT_TIMEOUT);
        return dispatcher.dispatchAndMerge(specs);
    }
}
