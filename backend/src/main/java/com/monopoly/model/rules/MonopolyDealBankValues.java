package com.monopoly.model.rules;

import java.util.Locale;
import java.util.Map;

/**
 * Bank value (M) when action cards are deposited; matches physical card corners.
 */
public final class MonopolyDealBankValues {

    private static final Map<String, Integer> ACTION_BANK_M = Map.ofEntries(
            Map.entry("RENT", 1),
            Map.entry("RENT_DUAL", 1),
            Map.entry("DOUBLE_RENT", 1),
            Map.entry("PASS_GO", 1),
            Map.entry("BIRTHDAY", 2),
            Map.entry("STEAL_PROPERTY", 3),
            Map.entry("FORCED_DEAL", 3),
            Map.entry("DEBT_COLLECTOR", 3),
            Map.entry("HOUSE", 3),
            Map.entry("HOTEL", 4),
            Map.entry("RENT_WAIVER", 4),
            Map.entry("DEAL_BREAKER", 5)
    );

    private MonopolyDealBankValues() {
    }

    /**
     * @param effectCode action effect code
     * @return bank value in M, default 3
     */
    public static int bankValueForActionEffect(String effectCode) {
        if (effectCode == null || effectCode.isBlank()) {
            return 3;
        }
        String k = effectCode.trim().toUpperCase(Locale.ROOT);
        return ACTION_BANK_M.getOrDefault(k, 3);
    }

    /**
     * Stable map for rules UI.
     */
    public static Map<String, Integer> actionBankValuesUnmodifiable() {
        return ACTION_BANK_M;
    }
}
