package com.monopoly.pattern.strategy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientConfigTest {

    private String previousPreference;

    @BeforeEach
    void rememberProperty() {
        previousPreference = System.getProperty("monopoly.deepseek.preferFallbackForStrictJson");
    }

    @AfterEach
    void restoreProperty() {
        if (previousPreference == null) {
            System.clearProperty("monopoly.deepseek.preferFallbackForStrictJson");
        } else {
            System.setProperty("monopoly.deepseek.preferFallbackForStrictJson", previousPreference);
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
}
