package com.claw.provider;

import java.util.List;

/**
 * OpenAI Chat Completions API provider.
 *
 * <p>Uses {@code OPENAI_API_KEY} from the environment or {@code .claw-java/config}.
 */
public final class OpenAiProvider extends OpenAiCompatibleProvider {

    public OpenAiProvider() {
        super();
    }

    /** @param apiKey explicit API key; reads from env/.claw-java if null */
    public OpenAiProvider(String apiKey) {
        super(apiKey);
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    protected String apiUrl() {
        return "https://api.openai.com/v1/chat/completions";
    }

    @Override
    protected String envVarName() {
        return "OPENAI_API_KEY";
    }

    @Override
    public List<String> supportedModels() {
        return List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o4-mini", "o3-mini");
    }
}
