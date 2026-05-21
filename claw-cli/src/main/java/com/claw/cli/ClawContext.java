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
                    try {
                        provider.completeStreaming(req, onToken, result::set);
                    } catch (IOException e) {
                        // Fall back to non-streaming on streaming failure
                        ProviderResponse resp = provider.complete(req);
                        if (resp instanceof ProviderResponse.TextResponse t) {
                            onToken.accept(t.content());
                        }
                        return toLoopResponse(resp);
                    }
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
     *
     * <p>API keys are resolved from: env var → {@code ./.claw-java/config} →
     * {@code ~/.claw-java/config}.</p>
     */
    public static ClawContext createDefault() {
        try {
            var key = com.claw.core.ClawConfig.get("ANTHROPIC_API_KEY");
            ProviderRegistry.register(new AnthropicProvider(key));
        } catch (IllegalStateException e) {
            // ANTHROPIC_API_KEY not set — skip
        }
        try {
            var key = com.claw.core.ClawConfig.get("DEEPSEEK_API_KEY");
            ProviderRegistry.register(new DeepSeekProvider(key));
        } catch (IllegalStateException e) {
            // DEEPSEEK_API_KEY not set — skip
        }
        try {
            var key = com.claw.core.ClawConfig.get("OPENAI_API_KEY");
            ProviderRegistry.register(new OpenAiProvider(key));
        } catch (IllegalStateException e) {
            // OPENAI_API_KEY not set — skip
        }

        // Fail fast if no provider could be registered
        if (ProviderRegistry.listAll().isEmpty()) {
            throw new IllegalStateException("""
                    No provider available. Set an API key in one of:
                      - Environment variable: DEEPSEEK_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY
                      - ./.claw-java/config (project-level)
                      - ~/.claw-java/config (user-level)
                    Config file format:
                      DEEPSEEK_API_KEY=sk-...""");
        }
        String defaultProvider = ProviderRegistry.listAll().getFirst();
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
        cfg = cfg.withSystemPrompt("""
                You are Claw, an AI coding assistant running inside Claw-Java — \
                a Java 21 port of the Claude Code agent architecture. \
                You are helpful, knowledgeable, and direct. \
                You can read/write files, execute shell commands, search code, and fetch web pages. \
                Always identify yourself as Claw (🦞), never as Claude or any other assistant. \
                Respond in the same language the user uses.""");
        return new ClawContext(ProviderRegistry.defaultProvider(), registry, cfg, defaultModel);
    }
}
