package com.claw.provider;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenAI Chat Completions API provider.
 *
 * <p>Uses {@code OPENAI_API_KEY} from the environment and calls
 * {@code POST https://api.openai.com/v1/chat/completions}.
 * Supports synchronous and streaming completions with tool-call parsing.
 */
public final class OpenAiProvider implements Provider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final String apiKey;

    public OpenAiProvider() {
        this.apiKey = requireEnv("OPENAI_API_KEY");
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(Duration.ofSeconds(120))
                .callTimeout(Duration.ofSeconds(120))
                .build();
    }

    // --- Provider implementation ---

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public ProviderResponse complete(ProviderRequest pr) throws IOException, InterruptedException {
        var httpRequest = buildRequest(pr, false);
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw apiError(response, "OpenAI");
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
        factory.newEventSource(httpRequest, new EventSourceListener() {

            private final StringBuilder textBuf = new StringBuilder();
            private final Map<Integer, ToolCallDelta> toolCallDeltas = new java.util.HashMap<>();

            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (data == null || "[DONE]".equals(data.trim())) return;
                try {
                    JsonNode root = MAPPER.readTree(data);
                    JsonNode choices = root.get("choices");
                    if (choices == null || !choices.isArray() || choices.isEmpty()) return;

                    JsonNode delta = choices.get(0).get("delta");
                    if (delta == null) return;

                    // Text content
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String token = delta.get("content").asText();
                        textBuf.append(token);
                        if (onToken != null) onToken.accept(token);
                    }

                    // Tool calls
                    if (delta.has("tool_calls")) {
                        for (JsonNode tc : delta.get("tool_calls")) {
                            int index = tc.get("index").asInt();
                            ToolCallDelta tcd = toolCallDeltas.computeIfAbsent(index, k -> new ToolCallDelta());
                            if (tc.has("id")) tcd.id = tc.get("id").asText();
                            JsonNode fn = tc.get("function");
                            if (fn != null) {
                                if (fn.has("name")) tcd.name = fn.get("name").asText();
                                if (fn.has("arguments")) tcd.arguments.append(fn.get("arguments").asText());
                            }
                        }
                    }

                    // Check finish_reason
                    String finishReason = choices.get(0).has("finish_reason")
                            ? choices.get(0).get("finish_reason").asText() : null;

                    if ("stop".equals(finishReason) || "tool_calls".equals(finishReason)) {
                        es.cancel();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse SSE event: {}", e.getMessage());
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                deliverResult();
                future.complete(null);
            }

            @Override
            public void onFailure(EventSource es, Throwable t, okhttp3.Response resp) {
                log.error("SSE stream failure", t);
                deliverResult();
                future.completeExceptionally(t != null ? t : new IOException("SSE stream failed"));
            }

            private void deliverResult() {
                if (!toolCallDeltas.isEmpty()) {
                    List<ProviderRequest.ToolCall> calls = new ArrayList<>();
                    for (ToolCallDelta tcd : toolCallDeltas.values()) {
                        Map<String, Object> args;
                        try {
                            args = MAPPER.readValue(tcd.arguments.toString(), new TypeReference<>() {});
                        } catch (Exception e) {
                            log.warn("Failed to parse tool call arguments: {}", e.getMessage());
                            args = Collections.emptyMap();
                        }
                        calls.add(new ProviderRequest.ToolCall(tcd.id, tcd.name, args));
                    }
                    if (onComplete != null) {
                        onComplete.accept(new ProviderResponse.ToolCallResponse(
                                Collections.unmodifiableList(calls)));
                    }
                } else {
                    if (onComplete != null) {
                        onComplete.accept(new ProviderResponse.TextResponse(textBuf.toString()));
                    }
                }
            }
        });

        try {
            future.get();
        } catch (Exception e) {
            throw new IOException("Streaming interrupted", e);
        }
    }

    @Override
    public List<String> supportedModels() {
        return List.of(
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo",
                "o4-mini",
                "o3-mini"
        );
    }

    // --- Private helpers ---

    private Request buildRequest(ProviderRequest pr, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", pr.model());
        if (pr.maxTokens() > 0) body.put("max_tokens", pr.maxTokens());
        else body.put("max_tokens", DEFAULT_MAX_TOKENS);
        if (pr.temperature() > 0) body.put("temperature", pr.temperature());
        if (stream) body.put("stream", true);

        // Messages
        ArrayNode messagesArray = body.putArray("messages");
        for (var msg : pr.messages()) {
            ObjectNode m = messagesArray.addObject();
            m.put("role", msg.role());
            if (msg.name() != null) m.put("name", msg.name());

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                // Assistant message with tool calls
                ArrayNode tcArray = m.putArray("tool_calls");
                for (var tc : msg.toolCalls()) {
                    ObjectNode tcNode = tcArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode fn = tcNode.putObject("function");
                    fn.put("name", tc.name());
                    try {
                        fn.put("arguments", MAPPER.writeValueAsString(tc.arguments()));
                    } catch (Exception e) {
                        fn.put("arguments", "{}");
                    }
                }
            } else if (msg.toolCallId() != null) {
                // Tool result message
                m.put("tool_call_id", msg.toolCallId());
            }

            if (msg.content() != null) {
                m.put("content", msg.content());
            }
        }

        // Tools
        if (pr.tools() != null && !pr.tools().isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (var tool : pr.tools()) {
                ObjectNode t = toolsArray.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", MAPPER.valueToTree(tool.parameters()));
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
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();
    }

    private static IOException apiError(Response response, String provider) {
        String body = "";
        try {
            if (response.body() != null) body = response.body().string();
        } catch (Exception ignored) {}
        return new IOException(provider + " API error " + response.code() + ": " + body);
    }

    private static String requireEnv(String var) {
        String val = System.getenv(var);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + var);
        }
        return val;
    }

    // --- Helper for streaming tool call accumulation ---

    private static final class ToolCallDelta {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }
}
