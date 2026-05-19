package com.claw.provider;

import com.claw.core.model.Message;
import com.claw.core.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Anthropic Messages API provider.
 *
 * <p>Uses {@code ANTHROPIC_API_KEY} from the environment and calls
 * {@code POST https://api.anthropic.com/v1/messages}.
 * Supports both synchronous and streaming (SSE) completions,
 * including tool-use block parsing.</p>
 */
public final class AnthropicProvider implements Provider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final String apiKey;

    public AnthropicProvider() {
        this.apiKey = requireEnv("ANTHROPIC_API_KEY");
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(Duration.ofSeconds(120))
                .callTimeout(Duration.ofSeconds(120))
                .build();
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public ProviderResponse complete(ProviderRequest pr) throws IOException, InterruptedException {
        var httpRequest = buildRequest(pr, false);
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw apiError(response);
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return ProviderResponse.parse(body);
        }
    }

    @Override
    public void completeStreaming(
            ProviderRequest pr,
            Consumer<String> onToken,
            Consumer<ProviderResponse> onComplete) throws IOException {

        var httpRequest = buildRequest(pr, true);
        var future = new CompletableFuture<Void>();

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        EventSource eventSource = factory.newEventSource(httpRequest, new EventSourceListener() {

            private final StringBuilder textBuf = new StringBuilder();
            private final Map<Integer, ToolCallBuilder> toolCallBuilders = new HashMap<>();
            private final List<ToolCall> completedToolCalls = new ArrayList<>();

            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (data == null || data.isBlank()) return;
                try {
                    JsonNode event = MAPPER.readTree(data);

                    switch (type) {
                        case "content_block_start" -> {
                            JsonNode block = event.get("content_block");
                            if (block != null && "tool_use".equals(block.get("type").asText())) {
                                int index = event.get("index").asInt();
                                toolCallBuilders.put(index, new ToolCallBuilder(
                                        block.get("id").asText(),
                                        block.get("name").asText()));
                            }
                        }
                        case "content_block_delta" -> {
                            JsonNode delta = event.get("delta");
                            if (delta == null) break;
                            String deltaType = delta.get("type").asText();
                            if ("text_delta".equals(deltaType) && delta.has("text")) {
                                String token = delta.get("text").asText();
                                textBuf.append(token);
                                if (onToken != null) onToken.accept(token);
                            } else if ("input_json_delta".equals(deltaType) && delta.has("partial_json")) {
                                int index = event.get("index").asInt();
                                ToolCallBuilder tcb = toolCallBuilders.get(index);
                                if (tcb != null) {
                                    tcb.appendJson(delta.get("partial_json").asText());
                                }
                            }
                        }
                        case "content_block_stop" -> {
                            int index = event.get("index").asInt();
                            ToolCallBuilder tcb = toolCallBuilders.remove(index);
                            if (tcb != null) {
                                completedToolCalls.add(tcb.build());
                            }
                        }
                        default -> {
                            // message_start, ping, message_stop etc. — ignore
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse SSE event: {}", e.getMessage());
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                ProviderResponse result;
                if (!completedToolCalls.isEmpty()) {
                    result = new ProviderResponse.ToolCallResponse(
                            Collections.unmodifiableList(completedToolCalls));
                } else {
                    result = new ProviderResponse.TextResponse(textBuf.toString());
                }
                if (onComplete != null) onComplete.accept(result);
                future.complete(null);
            }

            @Override
            public void onFailure(EventSource es, Throwable t, okhttp3.Response resp) {
                log.error("SSE stream failure", t);
                if (onComplete != null) {
                    onComplete.accept(new ProviderResponse.TextResponse(textBuf.toString()));
                }
                future.completeExceptionally(t != null ? t : new IOException("SSE stream failed"));
            }
        });

        try {
            future.get(); // block until stream completes
        } catch (Exception e) {
            throw new IOException("Streaming interrupted", e);
        }
    }

    @Override
    public List<String> supportedModels() {
        return List.of(
                "claude-sonnet-4-20250514",
                "claude-opus-4-20250514",
                "claude-haiku-3-5-20250515"
        );
    }

    // --- Request building (uses core Message types) ---

    private Request buildRequest(ProviderRequest pr, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", pr.model());
        body.put("max_tokens", pr.maxTokens() > 0 ? pr.maxTokens() : DEFAULT_MAX_TOKENS);
        if (stream) body.put("stream", true);

        ArrayNode messagesArray = body.putArray("messages");

        // Anthropic expects system prompt as a top-level "system" field
        for (var msg : pr.messages()) {
            if (msg.role() == Message.Role.SYSTEM) {
                String sysContent = msg.textContent();
                if (sysContent != null && !sysContent.isBlank()) {
                    if (body.has("system")) {
                        body.put("system", body.get("system").asText() + "\n" + sysContent);
                    } else {
                        body.put("system", sysContent);
                    }
                }
            } else {
                ObjectNode m = messagesArray.addObject();
                m.put("role", msg.role().name().toLowerCase());

                // Assistant message with tool calls
                if (msg.hasToolCalls()) {
                    ArrayNode toolCallsNode = m.putArray("content");
                    String textContent = msg.textContent();
                    if (textContent != null && !textContent.isBlank()) {
                        ObjectNode tcNode = toolCallsNode.addObject();
                        tcNode.put("type", "text");
                        tcNode.put("text", textContent);
                    }
                    for (var tc : msg.toolCalls()) {
                        ObjectNode tcNode = toolCallsNode.addObject();
                        tcNode.put("type", "tool_use");
                        tcNode.put("id", tc.id());
                        tcNode.put("name", tc.name());
                        tcNode.set("input", MAPPER.valueToTree(tc.arguments()));
                    }
                }
                // Tool result message
                else if (msg.toolCallId() != null) {
                    ObjectNode toolResult = m.putObject("content");
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", msg.toolCallId());
                    String content = msg.textContent();
                    toolResult.put("content", content != null ? content : "");
                }
                // Regular text message
                else {
                    String content = msg.textContent();
                    if (content != null) {
                        m.put("content", content);
                    }
                }
            }
        }

        // Tools
        if (pr.tools() != null && !pr.tools().isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (var tool : pr.tools()) {
                ObjectNode t = toolsArray.addObject();
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("input_schema", MAPPER.valueToTree(tool.parameters()));
            }
        }

        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request", e);
        }

        return new Request.Builder()
                .url(API_URL)
                .post(RequestBody.create(json, JSON))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .build();
    }

    private IOException apiError(Response response) {
        String body = "";
        try {
            if (response.body() != null) body = response.body().string();
        } catch (Exception ignored) {}
        return new IOException("Anthropic API error " + response.code() + ": " + body);
    }

    private static String requireEnv(String var) {
        String val = System.getenv(var);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + var);
        }
        return val;
    }

    // --- Helper for streaming tool call accumulation ---

    private static final class ToolCallBuilder {
        private final String id;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        ToolCallBuilder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        void appendJson(String fragment) {
            arguments.append(fragment);
        }

        @SuppressWarnings("unchecked")
        ToolCall build() {
            Map<String, Object> args = Map.of();
            String argsStr = arguments.toString();
            if (!argsStr.isBlank()) {
                try {
                    args = MAPPER.readValue(argsStr, Map.class);
                } catch (Exception e) {
                    log.warn("Failed to parse tool call arguments: {}", argsStr);
                }
            }
            return new ToolCall(id, name, args);
        }
    }
}
