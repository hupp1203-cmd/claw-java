package com.claw.cli;

import com.claw.core.AgentConfig;
import com.claw.core.AgentLoop;
import com.claw.core.AgentLoop.LoopResponse;
import com.claw.core.QueryEngine;
import com.claw.core.model.ToolCall;
import com.claw.core.model.ToolResult;
import com.claw.provider.*;
import com.claw.tools.Tool;
import com.claw.tools.ToolRegistry;
import com.claw.tools.builtin.BashTool;
import com.claw.tools.builtin.EditTool;
import com.claw.tools.builtin.FindTool;
import com.claw.tools.builtin.GrepTool;
import com.claw.tools.builtin.ReadFileTool;
import com.claw.tools.builtin.WebFetchTool;
import com.claw.tools.builtin.WriteFileTool;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Application context that wires together all components (manual DI).
 *
 * <p>Bridges {@code claw-provider} responses into the {@code claw-core}
 * {@link LoopResponse} type used by {@link AgentLoop} — no more JSON
 * round-trip or manual message conversion.</p>
 */
public class ClawContext {

    private Provider provider;
    private ToolRegistry toolRegistry;
    private AgentConfig config;
    private QueryEngine engine;
    private String model;

    public ClawContext(Provider provider, ToolRegistry toolRegistry,
                       AgentConfig config, String model) {
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.model = model;
        this.engine = createEngine();
    }

    public Provider provider() { return provider; }
    public ToolRegistry toolRegistry() { return toolRegistry; }
    public AgentConfig config() { return config; }
    public QueryEngine engine() { return engine; }
    public String model() { return model; }

    public void setModel(String newModel) {
        this.model = newModel;
        this.config = AgentConfig.of(model, config.provider());
        this.engine = createEngine();
    }

    public void clearConversation() {
        this.engine = createEngine();
    }

    private QueryEngine createEngine() {
        var cfg = AgentConfig.of(model, config.provider(), config.maxTokens(),
                config.systemPrompt(), config.maxToolRounds(), config.workingDirectory());

        // Provider bridge: ProviderRequest (core types) → ProviderResponse → LoopResponse
        AgentLoop.ProviderCallback providerCb = (messages, onToken) -> {
            var req = new ProviderRequest(model, messages,
                    cfg.maxTokens(), 0.7,
                    toToolDefs(toolRegistry.listAll()));
            try {
                // Use streaming if onToken is provided
                if (onToken != null) {
                    var result = new java.util.concurrent.atomic.AtomicReference<ProviderResponse>();
                    provider.completeStreaming(req, onToken, result::set);
                    ProviderResponse resp = result.get();
                    return toLoopResponse(resp);
                } else {
                    ProviderResponse resp = provider.complete(req);
                    return toLoopResponse(resp);
                }
            } catch (IOException e) {
                throw new AgentLoop.AgentLoopException("Provider error: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentLoop.AgentLoopException("Interrupted", e);
            }
        };

        // Tool bridge: core ToolCall → registry lookup
        AgentLoop.ToolExecutor toolExec = call -> {
            try {
                String result = toolRegistry.execute(call.name(), call.arguments());
                return ToolResult.success(call.id(), result);
            } catch (Exception e) {
                return ToolResult.error(call.id(), "Tool error: " + e.getMessage());
            }
        };

        return new QueryEngine(cfg, providerCb, toolExec);
    }

    /** Bridge ProviderResponse → LoopResponse. */
    private static LoopResponse toLoopResponse(ProviderResponse resp) {
        return switch (resp) {
            case ProviderResponse.TextResponse t -> new LoopResponse.Text(t.content());
            case ProviderResponse.ToolCallResponse tc -> {
                List<ToolCall> coreCalls = tc.toolCalls().stream()
                        .map(c -> new ToolCall(c.id(), c.name(), c.arguments()))
                        .toList();
                yield new LoopResponse.ToolUse(null, coreCalls);
            }
        };
    }

    /** Convert Tool list to ProviderRequest.ToolDef list. */
    private static List<ProviderRequest.ToolDef> toToolDefs(List<Tool> tools) {
        return tools.stream()
                .map(t -> new ProviderRequest.ToolDef(
                        t.name(), t.description(), t.parametersSchema()))
                .toList();
    }

    /**
     * Creates a default context with all built-in tools registered
     * and the Anthropic provider as default.
     */
    public static ClawContext createDefault() {
        try {
            ProviderRegistry.register(new AnthropicProvider());
        } catch (IllegalStateException e) {
            // ANTHROPIC_API_KEY not set — skip
        }
        try {
            ProviderRegistry.register(new DeepSeekProvider());
        } catch (IllegalStateException e) {
            // DEEPSEEK_API_KEY not set — skip
        }
        try {
            ProviderRegistry.register(new OpenAiProvider());
        } catch (IllegalStateException e) {
            // OPENAI_API_KEY not set — skip
        }

        // Pick first available provider
        String defaultProvider = ProviderRegistry.listAll().isEmpty()
                ? "anthropic"
                : ProviderRegistry.listAll().getFirst();
        String defaultModel = switch (defaultProvider) {
            case "deepseek" -> "deepseek-chat";
            case "openai" -> "gpt-4o";
            default -> "claude-sonnet-4-20250514";
        };

        var registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new GrepTool());
        registry.register(new EditTool());
        registry.register(new WebFetchTool());
        registry.register(new FindTool());

        var cfg = AgentConfig.of(defaultModel, defaultProvider);
        return new ClawContext(ProviderRegistry.defaultProvider(), registry, cfg, defaultModel);
    }
}
