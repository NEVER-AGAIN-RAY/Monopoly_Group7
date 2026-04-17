package com.monopoly.model.settlement;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.Player;

import java.util.Locale;

/**
 * 收租金额：与实体 Monopoly Deal 一致——须该色<strong>完整套</strong>方可收租；
 * 基础租来自 {@link RentTierTable}（按该色张数查表，多套同色分段累计），
 * 再叠加各张房产上的房屋 +3M / 旅馆 +7M（相对平地）。
 */
public final class RentCalculator {

    private RentCalculator() {
    }

    /**
     * @return 应付租金（M）；该色未形成完整套时为 0
     */
    public static int computeRentForColor(Player landlord, String colorKey) {
        if (landlord == null || colorKey == null || colorKey.isBlank()) {
            return 0;
        }
        String key = colorKey.trim().toUpperCase(Locale.ROOT);
        if (!PropertySetCalculator.hasCompleteSetForColor(landlord.getPropertyCardsView(), key)) {
            return 0;
        }
        int n = PropertySetCalculator.effectiveCountForColor(landlord.getPropertyCardsView(), key);
        if (n <= 0) {
            return 0;
        }
        int base = RentTierTable.baseRentForPropertyCount(key, n);
        int building = 0;
        for (PropertyCard p : landlord.getPropertyCardsView()) {
            if (p == null) {
                continue;
            }
            if (matchesColor(p, key)) {
                building += buildingBonusM(p);
            }
        }
        return base + building;
    }

    private static boolean matchesColor(PropertyCard p, String key) {
        if (p.isWildProperty() && p instanceof PropertyWildCard w) {
            String ak = w.getAssignedColorKey();
            return ak != null && key.equals(ak.trim().toUpperCase(Locale.ROOT));
        }
        String cg = p.getColorGroup();
        return cg != null && key.equals(cg.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 单张房产对收租的<strong>建筑加值</strong>（房屋 +3M；旅馆共 +7M），不含基础租表。
     */
    public static int buildingBonusM(PropertyCard card) {
        if (card == null) {
            return 0;
        }
        return switch (card.getBuildingLevel()) {
            case BASE -> 0;
            case HOUSE -> 3;
            case HOTEL -> 7;
        };
    }

    /**
     * 兼容旧调用：返回「建筑加值」（基础租改由套内总表计算，不再按张加基础）。
     */
    public static int rentForOneProperty(PropertyCard card, String rentColorKey) {
        return buildingBonusM(card);
    }

    public static int rentForOneProperty(PropertyCard card) {
        return buildingBonusM(card);
    }

    /**
     * 该色仅 1 张时的表上基础租，供协议/UI。
     */
    public static int baseRentOnlyForColor(String colorGroup) {
        if (colorGroup == null || colorGroup.isBlank()) {
            return 0;
        }
        return RentTierTable.firstTierRent(colorGroup.trim().toUpperCase(Locale.ROOT));
    }
}
