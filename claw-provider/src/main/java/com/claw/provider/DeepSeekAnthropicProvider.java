package com.claw.provider;

import com.claw.core.ClawConfig;
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
 * DeepSeek provider using the Anthropic-compatible endpoint.
 *
 * <p>Calls {@code https://api.deepseek.com/anthropic/v1/messages} with
 * Anthropic message format, allowing DeepSeek models to be used with the
 * same protocol as Claude.
 *
 * <p>Supported models:
 * <ul>
 *   <li>{@code deepseek-v4-pro[1m]} — main model, 1M context</li>
 *   <li>{@code deepseek-v4-flash} — fast/cheap model</li>
 * </ul>
 */
public final class DeepSeekAnthropicProvider implements Provider {

    public static final String MODEL_PRO   = "deepseek-v4-pro[1m]";
    public static final String MODEL_FLASH = "deepseek-v4-flash";

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAnthropicProvider.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String DEFAULT_API_URL = "https://api.deepseek.com/anthropic/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 8192;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final String apiKey;

    public DeepSeekAnthropicProvider() {
        this(null);
    }

    /** @param explicitApiKey if non-null, use this key; otherwise read from config */
    public DeepSeekAnthropicProvider(String explicitApiKey) {
        String key = explicitApiKey != null && !explicitApiKey.isBlank()
                ? explicitApiKey
                : ClawConfig.get("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Missing required config: DEEPSEEK_API_KEY");
        }
        this.apiKey = key;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(Duration.ofSeconds(120))
                .callTimeout(Duration.ofSeconds(120))
                .build();
    }

    private String apiUrl() {
        String endpoint = ClawConfig.get("DEEPSEEK_ANTHROPIC_ENDPOINT");
        return (endpoint != null && !endpoint.isBlank()) ? endpoint : DEFAULT_API_URL;
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public List<String> supportedModels() {
        return List.of(MODEL_PRO, MODEL_FLASH);
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
        var done = new java.util.concurrent.atomic.AtomicBoolean(false);

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
                                if (tcb != null) tcb.appendJson(delta.get("partial_json").asText());
                            }
                        }
                        case "content_block_stop" -> {
                            int index = event.get("index").asInt();
                            ToolCallBuilder tcb = toolCallBuilders.remove(index);
                            if (tcb != null) completedToolCalls.add(tcb.build());
                        }
                        default -> { /* message_start, ping, message_stop — ignore */ }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse SSE event (type={}): {}", type, e.getMessage());
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                if (done.compareAndSet(false, true)) deliverResult();
                if (!future.isDone()) future.complete(null);
            }

            @Override
            public void onFailure(EventSource es, Throwable t, okhttp3.Response resp) {
                if (done.compareAndSet(false, true)) {
                    String respBody = "";
                    if (resp != null && resp.body() != null) {
                        try { respBody = resp.body().string(); } catch (Exception ignored) {}
                    }
                    log.error("SSE stream failure (HTTP {}): {} | body: {}",
                            resp != null ? resp.code() : "?",
                            t != null ? t.getMessage() : "null",
                            respBody);
                    deliverResult();
                }
                if (!future.isDone()) {
                    future.completeExceptionally(t != null ? t : new IOException("SSE stream failed"));
                }
            }

            private void deliverResult() {
                ProviderResponse result = completedToolCalls.isEmpty()
                        ? new ProviderResponse.TextResponse(textBuf.toString())
                        : new ProviderResponse.ToolCallResponse(Collections.unmodifiableList(completedToolCalls));
                if (onComplete != null) onComplete.accept(result);
            }
        });

        try {
            future.get(httpClient.callTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            eventSource.cancel();
            throw new IOException("Streaming timed out", e);
        } catch (InterruptedException e) {
            eventSource.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Streaming interrupted", e);
        } catch (Exception e) {
            eventSource.cancel();
            throw new IOException("Streaming failed", e);
        }
    }

    private Request buildRequest(ProviderRequest pr, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", pr.model());
        body.put("max_tokens", pr.maxTokens() > 0 ? pr.maxTokens() : DEFAULT_MAX_TOKENS);
        if (stream) body.put("stream", true);
        // Disable thinking mode — tool calls require thinking blocks to be echoed back,
        // but our message model doesn't store them. Disable to keep multi-turn tool use working.
        body.putObject("thinking").put("type", "disabled");

        ArrayNode messagesArray = body.putArray("messages");

        List<Message> msgs = pr.messages();
        int i = 0;
        while (i < msgs.size()) {
            Message msg = msgs.get(i);

            if (msg.role() == Message.Role.SYSTEM) {
                String sysContent = msg.textContent();
                if (sysContent != null && !sysContent.isBlank()) {
                    if (body.has("system")) {
                        body.put("system", body.get("system").asText() + "\n" + sysContent);
                    } else {
                        body.put("system", sysContent);
                    }
                }
                i++;
            } else if (msg.hasToolCalls()) {
                // assistant message with tool_use blocks
                ObjectNode m = messagesArray.addObject();
                m.put("role", "assistant");
                ArrayNode content = m.putArray("content");
                String text = msg.textContent();
                if (text != null && !text.isBlank()) {
                    content.addObject().put("type", "text").put("text", text);
                }
                for (var tc : msg.toolCalls()) {
                    ObjectNode tcNode = content.addObject();
                    tcNode.put("type", "tool_use");
                    tcNode.put("id", tc.id());
                    tcNode.put("name", tc.name());
                    tcNode.set("input", MAPPER.valueToTree(tc.arguments()));
                }
                i++;
            } else if (msg.toolCallId() != null) {
                // Collect ALL consecutive tool_result messages into one user message.
                // Anthropic requires all tool_results for a given assistant turn to be
                // in a single user message content array.
                ObjectNode m = messagesArray.addObject();
                m.put("role", "user");
                ArrayNode content = m.putArray("content");
                while (i < msgs.size() && msgs.get(i).toolCallId() != null) {
                    Message toolMsg = msgs.get(i);
                    ObjectNode result = content.addObject();
                    result.put("type", "tool_result");
                    result.put("tool_use_id", toolMsg.toolCallId());
                    result.put("content", toolMsg.textContent() != null ? toolMsg.textContent() : "");
                    i++;
                }
            } else {
                ObjectNode m = messagesArray.addObject();
                m.put("role", msg.role().name().toLowerCase());
                String content = msg.textContent();
                if (content != null) m.put("content", content);
                i++;
            }
        }

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
            json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request", e);
        }

        return new Request.Builder()
                .url(apiUrl())
                .post(RequestBody.create(json, JSON))
                .header("Authorization", "Bearer " + apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .build();
    }

    private IOException apiError(Response response) {
        String body = "";
        try {
            if (response.body() != null) body = response.body().string();
        } catch (Exception e) {
            log.debug("Failed to read error body: {}", e.toString());
        }
        return new IOException("DeepSeek Anthropic API error " + response.code() + ": " + body);
    }

    private static final class ToolCallBuilder {
        private final String id;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        ToolCallBuilder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        void appendJson(String fragment) { arguments.append(fragment); }

        @SuppressWarnings("unchecked")
        ToolCall build() {
            String argsStr = arguments.toString();
            if (argsStr.isBlank()) return new ToolCall(id, name, Map.of());
            try {
                return new ToolCall(id, name, MAPPER.readValue(argsStr, Map.class));
            } catch (Exception e) {
                log.warn("Failed to parse tool args: {}", e.getMessage());
                return new ToolCall(id, name, Map.of("_raw", argsStr));
            }
        }
    }
}
