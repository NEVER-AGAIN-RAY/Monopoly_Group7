package com.monopoly.model.settlement;

import java.util.Locale;

/**
 * Monopoly Deal 实体房产牌租金表：按「该颜色在财产区内的张数」取整套基础租（不含房屋/旅馆加值）。
 * <p>
 * 数值与常见 Hasbro 版牌面一致（棕/浅蓝/粉/橙/红/黄/绿/深蓝/铁路/公共）；多套同色时按「若干整套 + 余张」分段相加。
 */
public final class RentTierTable {

    private RentTierTable() {
    }

    /**
     * {@code tiers[i]} 表示该色持有 {@code i + 1} 张时的<strong>整套</strong>基础租（M）。
     */
    public static int[] tiersForColor(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            return new int[0];
        }
        String k = colorKey.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "BROWN" -> new int[] {1, 2};
            case "LIGHT_BLUE" -> new int[] {1, 2, 3};
            case "PINK" -> new int[] {1, 2, 4};
            case "ORANGE" -> new int[] {1, 3, 5};
            case "RED" -> new int[] {2, 3, 6};
            case "YELLOW" -> new int[] {2, 4, 6};
            case "GREEN" -> new int[] {2, 4, 7};
            case "DARK_BLUE" -> new int[] {2, 4};
            case "RAILROAD" -> new int[] {1, 2, 3, 7};
            case "UTILITY" -> new int[] {1, 2};
            default -> new int[] {1, 2, 3};
        };
    }

    /** 持有 {@code propertyCount} 张该色房产时的<strong>基础租合计</strong>（未计房/旅馆）。 */
    public static int baseRentForPropertyCount(String colorKey, int propertyCount) {
        if (propertyCount <= 0) {
            return 0;
        }
        int[] tiers = tiersForColor(colorKey);
        if (tiers.length == 0) {
            return 0;
        }
        int need = tiers.length;
        int fullSets = propertyCount / need;
        int remainder = propertyCount % need;
        int total = fullSets * tiers[need - 1];
        if (remainder > 0) {
            total += tiers[remainder - 1];
        }
        return total;
    }

    /** 首张（1 张）对应的基础租，供 UI 提示。 */
    public static int firstTierRent(String colorKey) {
        int[] t = tiersForColor(colorKey);
        return t.length > 0 ? t[0] : 0;
    }
}
