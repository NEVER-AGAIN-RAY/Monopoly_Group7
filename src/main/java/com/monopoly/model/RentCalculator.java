package com.monopoly.model;

import java.util.Locale;

/**
 * 收租金额：根据收租方财产区指定颜色的房产完成度（基础/房子/酒店）汇总。
 * <p>
 * 仅当该颜色在财产区已形成至少 1 套完整地产集时，才对该颜色上的房产计租；
 * 万能房产牌不参与单色收租（颜色为 null）。
 */
public final class RentCalculator {

    private RentCalculator() {
    }

    /**
     * @param landlord 收租方（财产区在己方）
     * @param colorKey 收租针对的颜色（大写键，与 {@link PropertySetCalculator} 一致）
     * @return 应付租金总额（M），若该颜色未形成完整集则为 0
     */
    public static int computeRentForColor(Player landlord, String colorKey) {
        if (landlord == null || colorKey == null || colorKey.isBlank()) {
            return 0;
        }
        String key = colorKey.trim().toUpperCase(Locale.ROOT);
        int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(key, 3);
        int count = countNonWildPropertiesOfColor(landlord, key);
        if (count < need) {
            return 0;
        }
        int total = 0;
        for (PropertyCard p : landlord.getPropertyCardsView()) {
            if (p == null || p.isWildProperty()) {
                continue;
            }
            String cg = p.getColorGroup();
            if (cg == null) {
                continue;
            }
            if (!key.equals(cg.trim().toUpperCase(Locale.ROOT))) {
                continue;
            }
            total += rentForOneProperty(p);
        }
        return total;
    }

    private static int countNonWildPropertiesOfColor(Player landlord, String colorKey) {
        int n = 0;
        for (PropertyCard p : landlord.getPropertyCardsView()) {
            if (p == null || p.isWildProperty()) {
                continue;
            }
            String cg = p.getColorGroup();
            if (cg != null && colorKey.equals(cg.trim().toUpperCase(Locale.ROOT))) {
                n++;
            }
        }
        return n;
    }

    /**
     * 单张房产在本套内的收租贡献：基础租金 + 房子/酒店加值。
     */
    public static int rentForOneProperty(PropertyCard card) {
        if (card == null) {
            return 0;
        }
        int base = baseRentForColor(card.getColorGroup());
        int bonus = switch (card.getBuildingLevel()) {
            case BASE -> 0;
            case HOUSE -> 2;
            case HOTEL -> 5;
        };
        return base + bonus;
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
