package com.claw.tools.builtin;

import com.claw.tools.Tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Fetches a URL and returns the text content, stripping HTML tags.
 * <p>
 * Uses the JDK 11+ built-in {@link java.net.http.HttpClient} with a 30-second
 * timeout, follow-redirects, and a static {@code User-Agent} header.
 * Rough HTML stripping is done via regex {@code <[^>]*>} — functional for most
 * pages but not a true HTML parser.
 * </p>
 */
public class WebFetchTool implements Tool {

    private static final String USER_AGENT = "Claw-Java/1.0";
    private static final int DEFAULT_MAX_CHARS = 50000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetches a URL and returns stripped text content (HTML tags removed).";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "url", Map.of(
                    "type", "string",
                    "description", "HTTP(S) URL to fetch"
                ),
                "maxChars", Map.of(
                    "type", "integer",
                    "description", "Maximum characters to return",
                    "default", DEFAULT_MAX_CHARS
                )
            ),
            "required", List.of("url")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String urlStr = (String) arguments.get("url");
        if (urlStr == null || urlStr.isBlank()) {
            return "Error: url parameter is required";
        }

        Object maxCharsArg = arguments.get("maxChars");
        int maxChars = (maxCharsArg instanceof Number n)
                ? n.intValue()
                : DEFAULT_MAX_CHARS;

        URI uri;
        try {
            uri = URI.create(urlStr);
        } catch (IllegalArgumentException e) {
            return "Error: Invalid URL: " + urlStr;
        }

        if (isPrivateAddress(uri.getHost())) {
            return "Error: Access to private/internal addresses is not allowed: " + uri.getHost();
        }

        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            return "Error: Only HTTP and HTTPS URLs are supported";
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return "Error: Request timed out after 30 seconds";
        } catch (Exception e) {
            return "Error: Failed to fetch URL: " + e.getMessage();
        }

        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            return "Error: HTTP " + statusCode + " when fetching " + urlStr;
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return "Error: Empty response body";
        }

        // Rough HTML tag stripping
        String text = body.replaceAll("<[^>]*>", " ")
                          .replaceAll("&nbsp;", " ")
                          .replaceAll("\\s+", " ")
                          .trim();

        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "\n... (truncated)";
        }

        return text;
    }

    /**
     * Check if a hostname resolves to a private/internal IP address.
     */
    private static boolean isPrivateAddress(String host) {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            return addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress();
        } catch (Exception e) {
            return true; // block if can't resolve
        }
    }
}
