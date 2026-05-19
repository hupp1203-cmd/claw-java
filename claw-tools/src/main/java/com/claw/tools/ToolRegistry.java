package com.claw.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Registry that holds all registered tools and dispatches execution.
 * Thread-safe. Enforces per-tool timeouts via virtual threads.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** Register a tool by its name. */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** Get a tool by name, or null if not found. */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** List all registered tools (snapshot). */
    public List<Tool> listAll() {
        return List.copyOf(tools.values());
    }

    /** Execute a tool by name with the given arguments, enforcing the tool's timeout. */
    public String execute(String name, Map<String, Object> arguments) throws Exception {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "Error: Tool not found: " + name;
        }

        Duration timeout = tool.timeout();
        log.debug("Executing tool '{}' with timeout {}", name, timeout);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<String> task = () -> tool.execute(arguments);
            Future<String> future = executor.submit(task);
            Instant start = Instant.now();
            try {
                String result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                log.debug("Tool '{}' completed in {}ms",
                        name, Duration.between(start, Instant.now()).toMillis());
                return result;
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("Tool '{}' timed out after {}", name, timeout);
                return "Error: Tool execution timed out after " + timeout.toSeconds() + "s";
            }
        }
    }
}
