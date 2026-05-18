package com.claw.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable configuration for an agent run.
 *
 * <p>Captures everything needed to initialize an {@link AgentLoop}:
 * which model and provider to use, token budget, system prompt, tool
 * round limits, and the working directory for tool execution.</p>
 *
 * @param model            the model name (e.g. {@code "claude-sonnet-4-20250514"})
 * @param provider         the provider identifier (e.g. {@code "anthropic"}, {@code "openai"})
 * @param maxTokens        maximum tokens for the model's response
 * @param systemPrompt     the system prompt injected at the start of conversations
 * @param maxToolRounds    maximum number of tool-call round-trips per turn (default: 25)
 * @param workingDirectory the directory from which tools should execute
 */
public record AgentConfig(
        String model,
        String provider,
        int maxTokens,
        String systemPrompt,
        int maxToolRounds,
        Path workingDirectory) {

    /** Sensible default for maximum tool round-trips. */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 25;

    /** Default max output tokens. */
    public static final int DEFAULT_MAX_TOKENS = 8_192;

    /**
     * Canonical constructor with validation.
     *
     * <p>Note: Java record compact constructors cannot reassign parameters.
     * Use the static factory methods or overloaded constructors for defaults.</p>
     */
    public AgentConfig {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
    }

    /**
     * Creates a minimal configuration with sensible defaults.
     *
     * @param model    the model name
     * @param provider the provider identifier
     */
    public static AgentConfig of(String model, String provider) {
        return new AgentConfig(model, provider, DEFAULT_MAX_TOKENS, null,
                DEFAULT_MAX_TOOL_ROUNDS, Path.of(System.getProperty("user.dir")));
    }

    /**
     * Creates a configuration with explicit settings.
     */
    public static AgentConfig of(String model, String provider, int maxTokens,
                                  String systemPrompt, int maxToolRounds,
                                  Path workingDirectory) {
        return new AgentConfig(model, provider,
                maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS,
                systemPrompt,
                maxToolRounds > 0 ? maxToolRounds : DEFAULT_MAX_TOOL_ROUNDS,
                workingDirectory != null ? workingDirectory : Path.of(System.getProperty("user.dir")));
    }

    /**
     * Returns a copy of this config with the given system prompt.
     */
    public AgentConfig withSystemPrompt(String prompt) {
        return new AgentConfig(model, provider, maxTokens, prompt, maxToolRounds, workingDirectory);
    }

    /**
     * Returns a copy of this config with the given maximum tool rounds.
     */
    public AgentConfig withMaxToolRounds(int rounds) {
        return new AgentConfig(model, provider, maxTokens, systemPrompt,
                rounds > 0 ? rounds : DEFAULT_MAX_TOOL_ROUNDS, workingDirectory);
    }
}
