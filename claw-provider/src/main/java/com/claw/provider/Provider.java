package com.claw.provider;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Service Provider Interface (SPI) for model providers.
 * <p>
 * Implementations adapt provider-specific HTTP APIs (Anthropic, OpenAI, DeepSeek)
 * into the common {@link ProviderRequest} / {@link ProviderResponse} model.
 * Each provider is responsible for JSON serialization, authentication, and
 * response parsing.
 *
 * <p>Usage example:
 * <pre>{@code
 * Provider provider = ProviderRegistry.get("anthropic");
 * ProviderResponse resp = provider.complete(request);
 * }</pre>
 */
public interface Provider {

    /** Human-readable provider name (e.g. "anthropic", "openai"). */
    String name();

    /**
     * Send a synchronous completion request.
     *
     * @param request the request parameters
     * @return the model response (text or tool calls)
     * @throws IOException          on network or I/O errors
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    ProviderResponse complete(ProviderRequest request) throws IOException, InterruptedException;

    /**
     * Send a streaming completion request.
     * <p>
     * Text tokens are delivered one-by-one to {@code onToken}. When the stream
     * finishes, {@code onComplete} is called with the full parsed response
     * (which may include accumulated tool calls).
     *
     * @param request    the request parameters
     * @param onToken    consumer for each text token (may be {@code null})
     * @param onComplete consumer for the final aggregated response (may be {@code null})
     * @throws IOException          on network or I/O errors
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void completeStreaming(
            ProviderRequest request,
            Consumer<String> onToken,
            Consumer<ProviderResponse> onComplete) throws IOException, InterruptedException;

    /**
     * Return the list of model identifiers this provider supports.
     * Providers may return a static list or query a remote endpoint.
     */
    List<String> supportedModels();
}
