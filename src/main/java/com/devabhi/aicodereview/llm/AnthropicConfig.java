package com.devabhi.aicodereview.llm;

import java.time.Duration;

/** Connection settings. The API key never appears in toString, logs or error messages. */
public record AnthropicConfig(String apiKey, String baseUrl, String model, Duration timeout) {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    public AnthropicConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Anthropic API key is missing");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model name is missing");
        }
    }

    public static AnthropicConfig of(String apiKey, String model) {
        return new AnthropicConfig(apiKey, DEFAULT_BASE_URL, model, Duration.ofSeconds(120));
    }

    @Override
    public String toString() {
        return "AnthropicConfig[baseUrl=" + baseUrl + ", model=" + model + ", timeout=" + timeout
                + ", apiKey=***]";
    }
}