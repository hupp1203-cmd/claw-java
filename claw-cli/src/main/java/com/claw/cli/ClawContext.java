package com.claw.cli;

import com.claw.core.AgentConfig;
import com.claw.core.AgentLoop;
import com.claw.core.QueryEngine;
import com.claw.core.model.ToolResult;
import com.claw.provider.*;
import com.claw.tools.Tool;
import com.claw.tools.ToolRegistry;
import com.claw.tools.builtin.BashTool;
import com.claw.tools.builtin.GrepTool;
import com.claw.tools.builtin.ReadFileTool;
import com.claw.tools.builtin.WriteFileTool;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Application context that wires together all components (manual DI).
 * Bridges the claw-core model types to the claw-provider SPI types.
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

        // Bridge: claw-core Message list → ProviderRequest → raw JSON string
        AgentLoop.ProviderCallback providerCb = messages -> {
            var req = new ProviderRequest(model,
                    convertMessages(messages),
                    cfg.maxTokens(), 0.7,
                    toToolDefs(toolRegistry.listAll()));
            try {
                ProviderResponse resp = provider.complete(req);
                return toRawJson(resp);
            } catch (IOException e) {
                throw new AgentLoop.AgentLoopException("Provider error: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentLoop.AgentLoopException("Interrupted", e);
            }
        };

        // Bridge: claw-core ToolCall → toolRegistry.execute()
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

    /** Convert claw-core Message list to ProviderRequest.Message list. */
    private static List<ProviderRequest.Message> convertMessages(
            List<com.claw.core.model.Message> messages) {
        return messages.stream().map(m -> {
            String content = extractTextContent(m);
            List<ProviderRequest.ToolCall> toolCalls = m.toolCalls() != null
                    ? m.toolCalls().stream()
                        .map(tc -> new ProviderRequest.ToolCall(tc.id(), tc.name(), tc.arguments()))
                        .toList()
                    : null;
            return new ProviderRequest.Message(
                    m.role().name().toLowerCase(), content, null,
                    toolCalls, m.toolCallId());
        }).toList();
    }

    /** Extract text content from a claw-core Message. */
    private static String extractTextContent(com.claw.core.model.Message msg) {
        // Use the built-in textContent() convenience method
        String text = msg.textContent();
        if (text != null) return text;
        // Fallback: if it's a ContentBlock, try to get text
        if (msg.content() instanceof com.claw.core.model.Message.ContentBlock cb) {
            if (cb instanceof com.claw.core.model.Message.TextBlock tb) {
                return tb.text();
            }
            return cb.toString();
        }
        return msg.content() != null ? msg.content().toString() : "";
    }

    /** Convert ProviderResponse to raw JSON string for AgentLoop.parseResponse(). */
    private static String toRawJson(ProviderResponse resp) {
        if (resp instanceof ProviderResponse.TextResponse tr) {
            return "{\"choices\":[{\"message\":{\"content\":\"" + escapeJson(tr.content()) + "\"}}]}";
        } else if (resp instanceof ProviderResponse.ToolCallResponse tcr) {
            var sb = new StringBuilder();
            sb.append("{\"choices\":[{\"message\":{\"tool_calls\":[");
            var tcs = tcr.toolCalls();
            for (int i = 0; i < tcs.size(); i++) {
                if (i > 0) sb.append(",");
                var tc = tcs.get(i);
                sb.append("{\"id\":\"").append(tc.id()).append("\",");
                sb.append("\"type\":\"function\",");
                sb.append("\"function\":{\"name\":\"").append(tc.name()).append("\",");
                sb.append("\"arguments\":\"").append(escapeJson(tc.arguments().toString())).append("\"}}");
            }
            sb.append("]}}]}");
            return sb.toString();
        }
        return "{}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static List<ProviderRequest.ToolDef> toToolDefs(List<Tool> tools) {
        return tools.stream()
                .map(t -> new ProviderRequest.ToolDef(t.name(), t.description(),
                        Map.of("type", "object", "properties", t.parametersSchema())))
                .toList();
    }

    /**
     * Create a default ClawContext. Auto-detects available API keys.
     */
    public static ClawContext createDefault() {
        Provider provider;
        String model;

        String ak = System.getenv("ANTHROPIC_API_KEY");
        String ok = System.getenv("OPENAI_API_KEY");
        String dk = System.getenv("DEEPSEEK_API_KEY");

        if (ak != null && !ak.isBlank()) {
            provider = new AnthropicProvider();
            model = "claude-sonnet-4-20250514";
        } else if (ok != null && !ok.isBlank()) {
            provider = new OpenAiProvider();
            model = "gpt-4o";
        } else if (dk != null && !dk.isBlank()) {
            provider = new DeepSeekProvider();
            model = "deepseek-chat";
        } else {
            provider = new AnthropicProvider();
            model = "claude-sonnet-4-20250514";
        }

        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new GrepTool());

        AgentConfig cfg = AgentConfig.of(model, provider.name());

        return new ClawContext(provider, registry, cfg, model);
    }
}
