package com.claw.provider;

import com.claw.core.ClawConfig;

import java.util.List;

/**
 * DeepSeek API provider (OpenAI-compatible).
 *
 * <p>Uses {@code DEEPSEEK_API_KEY} from the environment or {@code .claw-java/config}.
 * The endpoint can be overridden via {@code DEEPSEEK_API_ENDPOINT} config key.
 *
 * <p>Supported models:
 * <ul>
 *   <li>{@code deepseek-v4-pro[1m]} — main model, 1M context</li>
 *   <li>{@code deepseek-v4-flash[1m]} — fast/cheap model, 1M context</li>
 * </ul>
 */
public final class DeepSeekProvider extends OpenAiCompatibleProvider {

    public static final String MODEL_PRO   = "deepseek-v4-pro[1m]";
    public static final String MODEL_FLASH = "deepseek-v4-flash[1m]";

    private static final String DEFAULT_ENDPOINT =
            "https://api.deepseek.com/v1/chat/completions";

    public DeepSeekProvider() {
        super();
    }

    /** @param apiKey explicit API key; reads from env/.claw-java if null */
    public DeepSeekProvider(String apiKey) {
        super(apiKey);
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    protected String apiUrl() {
        String endpoint = ClawConfig.get("DEEPSEEK_API_ENDPOINT");
        return (endpoint != null && !endpoint.isBlank()) ? endpoint : DEFAULT_ENDPOINT;
    }

    @Override
    protected String envVarName() {
        return "DEEPSEEK_API_KEY";
    }

    @Override
    public List<String> supportedModels() {
        return List.of(MODEL_PRO, MODEL_FLASH);
    }
}
