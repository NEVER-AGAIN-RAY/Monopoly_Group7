package com.monopoly.pattern.strategy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientConfigTest {

    private String previousPreference;
    private String previousApiKey;

    @BeforeEach
    void rememberProperty() {
        previousPreference = System.getProperty("monopoly.deepseek.preferFallbackForStrictJson");
        previousApiKey = System.getProperty("monopoly.deepseek.apiKey");
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
}
