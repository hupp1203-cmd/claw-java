package com.claw.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Interface for callable tools that agents can invoke.
 */
public interface Tool {

    /** Unique name of this tool. */
    String name();

    /** Human-readable description of what this tool does. */
    String description();

    /**
     * JSON Schema describing the parameters this tool accepts.
     * Uses standard JSON Schema format with "type", "properties", and "required" keys.
     */
    Map<String, Object> parametersSchema();

    /**
     * Execute the tool with the given arguments and return the result as a string.
     */
    String execute(Map<String, Object> arguments) throws Exception;

    /**
     * Returns the working directory for this tool's execution.
     * Defaults to the current user directory. Override to restrict tool
     * operations to a specific directory tree.
     */
    default Path workingDirectory() {
        return Path.of(System.getProperty("user.dir"));
    }

    /**
     * Returns the maximum duration this tool is allowed to run.
     * Defaults to 30 seconds. Override for long-running tools.
     */
    default Duration timeout() {
        return Duration.ofSeconds(30);
    }
}
