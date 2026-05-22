package com.monopoly.model.settlement;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

import java.util.List;
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

    public static boolean hasAnyBuildingForColor(List<PropertyCard> propertyZone, String colorKey) {
        if (propertyZone == null) {
            return false;
        }
        String key = normalize(colorKey);
        if (key == null) {
            return false;
        }
        for (PropertyCard p : propertyZone) {
            if (matchesColor(p, key) && p.getBuildingLevel() != BuildingLevel.BASE) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasHotelForColor(List<PropertyCard> propertyZone, String colorKey) {
        if (propertyZone == null) {
            return false;
        }
        String key = normalize(colorKey);
        if (key == null) {
            return false;
        }
        for (PropertyCard p : propertyZone) {
            if (matchesColor(p, key) && p.getBuildingLevel() == BuildingLevel.HOTEL) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesColor(PropertyCard card, String colorKey) {
        if (card == null || colorKey == null) {
            return false;
        }
        if (card.isWildProperty() && card instanceof PropertyWildCard wild) {
            String assigned = normalize(wild.getAssignedColorKey());
            return colorKey.equals(assigned);
        }
        String group = normalize(card.getColorGroup());
        return colorKey.equals(group);
    }

    private static String normalize(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            return null;
        }
        return colorKey.trim().toUpperCase(Locale.ROOT);
    }
}
