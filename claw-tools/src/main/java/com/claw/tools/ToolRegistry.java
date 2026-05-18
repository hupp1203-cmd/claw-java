package com.claw.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that holds all registered tools and dispatches execution.
 * Thread-safe.
 */
public class ToolRegistry {

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

    /** Execute a tool by name with the given arguments. */
    public String execute(String name, Map<String, Object> arguments) throws Exception {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "Error: Tool not found: " + name;
        }
        return tool.execute(arguments);
    }
}
