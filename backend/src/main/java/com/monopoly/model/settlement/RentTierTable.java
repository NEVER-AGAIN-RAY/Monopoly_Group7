package com.monopoly.model.settlement;

import java.util.Locale;

/**
 * Base rent tiers per color and card count (Hasbro-style); house/hotel added separately.
 */
public final class RentTierTable {

    private RentTierTable() {
    }

    /**
     * tiers[i] = base rent when holding i+1 cards of that color.
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

    /** 持有 propertyCount 张该色房产时的<strong>基础租合计</strong>（未计房/旅馆）。 */
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
