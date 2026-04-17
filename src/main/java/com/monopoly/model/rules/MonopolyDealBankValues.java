package com.monopoly.model.rules;

import java.util.Locale;
import java.util.Map;

/**
 * Monopoly Deal 行动牌「存入银行时可作现金」的面值（M），与实体牌角标一致。
 * <p>
 * 数值依据常见英文版说明（Hasbro / <a href="https://monopoly.fandom.com/wiki/Monopoly_Deal">Monopoly Wiki</a> 等）。
 * 本项目的 {@code effectCode} 与工厂 {@link com.monopoly.pattern.factory.MonopolyDealCardFactory} 中的行动牌分布一一对应。
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
     * @param effectCode 行动效果码（大小写不敏感）
     * @return 银行面值（M）；未知码返回 {@code 3} 作为保守缺省
     */
    public static int bankValueForActionEffect(String effectCode) {
        if (effectCode == null || effectCode.isBlank()) {
            return 3;
        }
        String k = effectCode.trim().toUpperCase(Locale.ROOT);
        return ACTION_BANK_M.getOrDefault(k, 3);
    }

    /**
     * 供规则面板等展示：效果码 → 面值，顺序稳定。
     */
    public static Map<String, Integer> actionBankValuesUnmodifiable() {
        return ACTION_BANK_M;
    }
}
