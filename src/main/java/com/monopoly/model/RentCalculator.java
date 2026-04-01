package com.monopoly.model;

import java.util.Locale;

/**
 * 收租金额：根据收租方财产区指定颜色的完成度汇总。
 * <p>
 * 仅当该颜色「纯色 + 已挂载万能」达到完整套时计租；万能牌按声明颜色参与基础租金与建筑加值。
 */
public final class RentCalculator {

    private RentCalculator() {
    }

    /**
     * @param landlord 收租方（财产区在己方）
     * @param colorKey   收租针对的颜色（大写键，与 {@link PropertySetCalculator} 一致）
     * @return 应付租金总额（M），若该颜色未形成完整集则为 0
     */
    public static int computeRentForColor(Player landlord, String colorKey) {
        if (landlord == null || colorKey == null || colorKey.isBlank()) {
            return 0;
        }
        String key = colorKey.trim().toUpperCase(Locale.ROOT);
        if (!PropertySetCalculator.hasCompleteSetForColor(landlord.getPropertyCardsView(), key)) {
            return 0;
        }
        int total = 0;
        for (PropertyCard p : landlord.getPropertyCardsView()) {
            if (p == null) {
                continue;
            }
            if (p.isWildProperty()) {
                if (p instanceof PropertyWildCard w) {
                    String ak = w.getAssignedColorKey();
                    if (ak != null && key.equals(ak)) {
                        total += rentForOneProperty(p, key);
                    }
                }
            } else {
                String cg = p.getColorGroup();
                if (cg != null && key.equals(cg.trim().toUpperCase(Locale.ROOT))) {
                    total += rentForOneProperty(p, key);
                }
            }
        }
        return total;
    }

    /**
     * 单张房产在本套内的收租贡献：基础租金 + 房子/酒店加值；{@code rentColorKey} 用于确定基础租金轨道。
     */
    public static int rentForOneProperty(PropertyCard card, String rentColorKey) {
        if (card == null) {
            return 0;
        }
        int base = baseRentForColor(rentColorKey);
        int bonus = switch (card.getBuildingLevel()) {
            case BASE -> 0;
            case HOUSE -> 2;
            case HOTEL -> 5;
        };
        return base + bonus;
    }

    public static int rentForOneProperty(PropertyCard card) {
        if (card == null) {
            return 0;
        }
        if (card.isWildProperty() && card instanceof PropertyWildCard w) {
            return rentForOneProperty(card, w.getAssignedColorKey());
        }
        return rentForOneProperty(card, card.getColorGroup());
    }

    private static int baseRentForColor(String colorGroup) {
        if (colorGroup == null || colorGroup.isBlank()) {
            return 2;
        }
        String k = colorGroup.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "BROWN", "DARK_BLUE" -> 1;
            case "LIGHT_BLUE", "PINK", "ORANGE", "RED", "YELLOW", "GREEN" -> 2;
            case "RAILROAD" -> 2;
            case "UTILITY" -> 1;
            default -> 2;
        };
    }
}
