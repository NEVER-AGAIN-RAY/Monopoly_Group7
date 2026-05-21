package com.monopoly.model.settlement;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.Player;

import java.util.Locale;

/**
 * Rent due: tier table by color count plus +3M per house and +7M per hotel.
 */
public final class RentCalculator {

    private RentCalculator() {
    }

    /**
     * @return rent in M, or 0 if no cards in color
     */
    public static int computeRentForColor(Player landlord, String colorKey) {
        if (landlord == null || colorKey == null || colorKey.isBlank()) {
            return 0;
        }
        String key = colorKey.trim().toUpperCase(Locale.ROOT);
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
     * Building bonus for one property (+3M house, +7M hotel).
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
     * Legacy helper: building bonus only.
     */
    public static int rentForOneProperty(PropertyCard card, String rentColorKey) {
        return buildingBonusM(card);
    }

    public static int rentForOneProperty(PropertyCard card) {
        return buildingBonusM(card);
    }

    /**
     * Base rent for a single card in color (UI preview).
     */
    public static int baseRentOnlyForColor(String colorGroup) {
        if (colorGroup == null || colorGroup.isBlank()) {
            return 0;
        }
        return RentTierTable.firstTierRent(colorGroup.trim().toUpperCase(Locale.ROOT));
    }
}
