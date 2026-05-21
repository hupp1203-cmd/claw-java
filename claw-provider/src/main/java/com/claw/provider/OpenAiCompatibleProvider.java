package com.claw.provider;

import com.claw.core.model.Message;
import com.claw.core.model.ToolCall;
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
 * Base class for OpenAI-compatible API providers.
 *
 * <p>Subclasses need only supply their API URL, environment variable name,
 * name string, and supported models list. All HTTP I/O, SSE parsing, and
 * request building is handled here.</p>
 */
public abstract class OpenAiCompatibleProvider implements Provider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int DEFAULT_MAX_TOKENS = 4096;
    static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final String apiKey;

    protected OpenAiCompatibleProvider() {
        this.apiKey = requireEnv(envVarName());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(Duration.ofSeconds(120))
                .callTimeout(Duration.ofSeconds(120))
                .build();
    }

    // --- Subclass contract ---

    protected abstract String apiUrl();
    protected abstract String envVarName();

    // --- Provider implementation ---

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
            private final Map<Integer, ToolCallDelta> toolCallDeltas = new java.util.HashMap<>();

            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (done.get() || data == null || "[DONE]".equals(data.trim())) return;
                try {
                    JsonNode root = MAPPER.readTree(data);
                    JsonNode choices = root.get("choices");
                    if (choices == null || !choices.isArray() || choices.isEmpty()) return;

                    JsonNode delta = choices.get(0).get("delta");
                    if (delta == null) return;

                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String token = delta.get("content").asText();
                        textBuf.append(token);
                        if (onToken != null) onToken.accept(token);
                    }

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

                    // Note: don't cancel or set done flag here — let onClosed
                    // deliver the result naturally when the server closes the stream
                } catch (Exception e) {
                    log.warn("Failed to parse SSE event: {}", e.getMessage(), e);
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                if (done.compareAndSet(false, true)) {
                    deliverResult();
                }
                if (!future.isDone()) {
                    future.complete(null);
                }
            }

            @Override
            public void onFailure(EventSource es, Throwable t, okhttp3.Response resp) {
                if (done.compareAndSet(false, true)) {
                    log.error("SSE stream failure", t);
                    deliverResult();
                }
                if (!future.isDone()) {
                    future.completeExceptionally(t != null ? t : new IOException("SSE stream failed"));
                }
            }

            private void deliverResult() {
                if (!toolCallDeltas.isEmpty()) {
                    List<ToolCall> calls = new ArrayList<>();
                    for (ToolCallDelta tcd : toolCallDeltas.values()) {
                        Map<String, Object> args = parseArgs(tcd.arguments.toString());
                        calls.add(new ToolCall(tcd.id, tcd.name, args));
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

            /** Parse tool call arguments, falling back to repair heuristics on truncation. */
            private Map<String, Object> parseArgs(String raw) {
                try {
                    return MAPPER.readValue(raw, new TypeReference<>() {});
                } catch (Exception e) {
                    // Try to repair truncated JSON by closing unclosed strings/objects
                    String repaired = repairTruncatedJson(raw);
                    if (repaired != null) {
                        try {
                            return MAPPER.readValue(repaired, new TypeReference<>() {});
                        } catch (Exception e2) {
                            // repair didn't work, fall through
                        }
                    }
                    log.warn("Failed to parse tool call arguments (len={}): {}", raw.length(), e.getMessage());
                    // Return raw content so the tool can still use it
                    return Map.of("_parse_error", "Arguments JSON was truncated at " + raw.length()
                            + " chars. Raw: " + raw.substring(Math.max(0, raw.length() - 200)));
                }
            }

            /** Attempt to close an unclosed JSON string and object. Returns null if unrecoverable. */
            private String repairTruncatedJson(String raw) {
                if (raw == null || raw.isEmpty()) return null;
                // Count opening/closing braces and quotes to determine what's missing
                int braceDepth = 0;
                boolean inString = false;
                for (int i = 0; i < raw.length(); i++) {
                    char c = raw.charAt(i);
                    if (c == '\\' && inString && i + 1 < raw.length()) {
                        i++; // skip escaped char
                        continue;
                    }
                    if (c == '"') inString = !inString;
                    else if (!inString && c == '{') braceDepth++;
                    else if (!inString && c == '}') braceDepth--;
                }
                StringBuilder sb = new StringBuilder(raw);
                if (inString) sb.append('"');
                while (braceDepth > 0) {
                    sb.append('}');
                    braceDepth--;
                }
                return sb.length() > raw.length() ? sb.toString() : null;
            }
        });

        try {
            future.get(httpClient.callTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new java.io.IOException("Streaming timed out", e);
        } catch (Exception e) {
            throw new IOException("Streaming interrupted", e);
        }
    }

    // --- Request building (uses core Message types) ---

    private Request buildRequest(ProviderRequest pr, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", pr.model());
        if (pr.maxTokens() > 0) body.put("max_tokens", pr.maxTokens());
        else body.put("max_tokens", DEFAULT_MAX_TOKENS);
        if (pr.temperature() > 0) body.put("temperature", pr.temperature());
        if (stream) body.put("stream", true);

        ArrayNode messagesArray = body.putArray("messages");
        for (var msg : pr.messages()) {
            ObjectNode m = messagesArray.addObject();
            m.put("role", msg.role().name().toLowerCase());

            // Assistant message with tool calls
            if (msg.hasToolCalls()) {
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
                // Include text content if present
                String text = msg.textContent();
                if (text != null && !text.isBlank()) {
                    m.put("content", text);
                }
            }
            // Tool result message
            else if (msg.toolCallId() != null) {
                m.put("tool_call_id", msg.toolCallId());
                String content = msg.textContent();
                if (content != null) m.put("content", content);
            }
            // Regular text message
            else {
                String content = msg.textContent();
                if (content != null) m.put("content", content);
            }
        }

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
                .url(apiUrl())
                .post(RequestBody.create(json, JSON))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();
    }

    private IOException apiError(Response response) {
        String body = "";
        try {
            if (response.body() != null) body = response.body().string();
        } catch (Exception e) {
            log.debug("Failed to read error response body: {}", e.toString());
        }
        return new IOException(name() + " API error " + response.code() + ": " + body);
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
