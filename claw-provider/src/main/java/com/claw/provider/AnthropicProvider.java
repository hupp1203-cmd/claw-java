package com.claw.provider;

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
 * including tool-use block parsing.
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

    // --- Provider implementation ---

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
            private final List<ProviderRequest.ToolCall> completedToolCalls = new ArrayList<>();

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
                        case "message_stop" -> {
                            es.cancel(); // close the stream cleanly
                        }
                        default -> {
                            // message_start, ping, etc. — ignore
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

    // --- Private helpers ---

    private Request buildRequest(ProviderRequest pr, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", pr.model());
        body.put("max_tokens", pr.maxTokens() > 0 ? pr.maxTokens() : DEFAULT_MAX_TOKENS);
        if (stream) body.put("stream", true);

        ArrayNode messagesArray = body.putArray("messages");

        // Anthropic expects system prompt as a top-level "system" field, not in messages
        for (var msg : pr.messages()) {
            if ("system".equals(msg.role())) {
                if (msg.content() != null) {
                    if (body.has("system")) {
                        // Append to existing system prompt
                        body.put("system", body.get("system").asText() + "\n" + msg.content());
                    } else {
                        body.put("system", msg.content());
                    }
                }
            } else {
                ObjectNode m = messagesArray.addObject();
                m.put("role", msg.role());
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    ArrayNode toolCallsNode = m.putArray("content");
                    for (var tc : msg.toolCalls()) {
                        ObjectNode tcNode = toolCallsNode.addObject();
                        tcNode.put("type", "tool_use");
                        tcNode.put("id", tc.id());
                        tcNode.put("name", tc.name());
                        tcNode.set("input", MAPPER.valueToTree(tc.arguments()));
                    }
                } else if (msg.toolCallId() != null) {
                    ObjectNode toolResult = m.putObject("content");
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", msg.toolCallId());
                    toolResult.put("content", msg.content() != null ? msg.content() : "");
                } else if (msg.content() != null) {
                    m.put("content", msg.content());
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

    private static IOException apiError(Response response) {
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

    // --- Tool call builder for streaming ---

    private static final class ToolCallBuilder {
        private final String id;
        private final String name;
        private final StringBuilder jsonBuf = new StringBuilder();

        ToolCallBuilder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        void appendJson(String partial) {
            jsonBuf.append(partial);
        }

        ProviderRequest.ToolCall build() {
            Map<String, Object> args;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = MAPPER.readValue(jsonBuf.toString(), Map.class);
                args = parsed;
            } catch (Exception e) {
                log.warn("Failed to parse tool call arguments: {}", e.getMessage());
                args = Collections.emptyMap();
            }
            return new ProviderRequest.ToolCall(id, name, args);
        }
    }
}
