package com.claw.provider;

import java.util.List;

/**
 * DeepSeek API provider (OpenAI-compatible).
 *
 * <p>Uses {@code DEEPSEEK_API_KEY} from the environment and calls
 * {@code POST https://api.deepseek.com/v1/chat/completions}.
 */
public final class DeepSeekProvider extends OpenAiCompatibleProvider {

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    protected String apiUrl() {
        return "https://api.deepseek.com/v1/chat/completions";
    }

    @Override
    protected String envVarName() {
        return "DEEPSEEK_API_KEY";
    }

    @Override
    public List<String> supportedModels() {
        return List.of("deepseek-chat", "deepseek-reasoner");
    }
}
