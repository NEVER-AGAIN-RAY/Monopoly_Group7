package com.monopoly.pattern.strategy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientConfigTest {

    private String previousPreference;
    private String previousApiKey;
    private String previousEnvFile;
    private String previousProvider;
    private String previousOpenAiApiKey;
    private String previousOpenAiEnvFile;

    @BeforeEach
    void rememberProperty() {
        previousPreference = System.getProperty("monopoly.deepseek.preferFallbackForStrictJson");
        previousApiKey = System.getProperty("monopoly.deepseek.apiKey");
        previousEnvFile = System.getProperty("monopoly.deepseek.envFile");
        previousProvider = System.getProperty("monopoly.llm.provider");
        previousOpenAiApiKey = System.getProperty("monopoly.openai.apiKey");
        previousOpenAiEnvFile = System.getProperty("monopoly.openai.envFile");
    }

    @AfterEach
    void restoreProperty() {
        if (previousPreference == null) {
            System.clearProperty("monopoly.deepseek.preferFallbackForStrictJson");
        } else {
            System.setProperty("monopoly.deepseek.preferFallbackForStrictJson", previousPreference);
        }
        if (previousApiKey == null) {
            System.clearProperty("monopoly.deepseek.apiKey");
        } else {
            System.setProperty("monopoly.deepseek.apiKey", previousApiKey);
        }
        if (previousEnvFile == null) {
            System.clearProperty("monopoly.deepseek.envFile");
        } else {
            System.setProperty("monopoly.deepseek.envFile", previousEnvFile);
        }
        restore("monopoly.llm.provider", previousProvider);
        restore("monopoly.openai.apiKey", previousOpenAiApiKey);
        restore("monopoly.openai.envFile", previousOpenAiEnvFile);
    }

    @Test
    void explicitFallbackPreferenceDisablesPreferredStrictJsonPath() {
        System.setProperty("monopoly.deepseek.preferFallbackForStrictJson", "true");

        assertTrue(DeepSeekClient.preferFallbackForStrictJson());
        assertFalse(new DeepSeekClient().willUsePreferredForStrictJson());
    }

    @Test
    void explicitFalseDoesNotPreferFallbackForStrictJson() {
        System.setProperty("monopoly.deepseek.preferFallbackForStrictJson", "false");

        assertFalse(DeepSeekClient.preferFallbackForStrictJson());
    }

    @Test
    void http429And5xxAreRetryable() {
        assertTrue(DeepSeekClient.isRetryable(new IOException("DeepSeek HTTP 429: rate limit")));
        assertTrue(DeepSeekClient.isRetryable(new IOException("DeepSeek HTTP 503: busy")));
    }

    @Test
    void validationErrorsAreNotRetryable() {
        assertFalse(DeepSeekClient.isRetryable(new IOException("DeepSeek HTTP 400: bad request")));
    }

    @Test
    void blankApiKeyPropertyDisablesImplicitDefaultKey() {
        System.setProperty("monopoly.deepseek.apiKey", " ");

        assertThrows(java.io.IOException.class,
                () -> new DeepSeekClient().complete("system", "user", false));
    }

    @Test
    void dotEnvCanProvideLocalApiKeyWithoutSystemProperty() throws IOException {
        Path env = Files.createTempFile("monopoly-deepseek", ".env");
        Files.writeString(env, """
                # local developer secret
                DEEPSEEK_API_KEY="local-test-key"
                """);
        System.clearProperty("monopoly.deepseek.apiKey");
        System.setProperty("monopoly.deepseek.envFile", env.toString());

        assertEquals("local-test-key", DeepSeekClient.configuredApiKeyForTest());
    }

    @Test
    void colonStyleGatewayConfigCanProvideOpenAiApiKey() throws IOException {
        Path env = Files.createTempFile("monopoly-openai", ".txt");
        Files.writeString(env, """
                gateway: example.local
                Apikey: local-openai-key
                """);
        System.setProperty("monopoly.llm.provider", "openai");
        System.clearProperty("monopoly.openai.apiKey");
        System.setProperty("monopoly.openai.envFile", env.toString());

        assertEquals("local-openai-key", DeepSeekClient.configuredApiKeyForTest());
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
