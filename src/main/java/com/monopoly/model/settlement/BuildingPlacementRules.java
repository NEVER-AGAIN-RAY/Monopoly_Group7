package com.monopoly.model.settlement;

import java.util.Locale;

/**
 * Whether house/hotel can be placed (not on railroad/utility).
 */
public final class BuildingPlacementRules {

    private BuildingPlacementRules() {
    }

    public static boolean allowsHouseHotel(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            return false;
        }
        String k = colorKey.trim().toUpperCase(Locale.ROOT);
        return !"RAILROAD".equals(k) && !"UTILITY".equals(k);
    }
}
