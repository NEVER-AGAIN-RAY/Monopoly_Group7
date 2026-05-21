package com.monopoly.model.settlement;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Complete set counting for win condition and building rules.
 */
public final class PropertySetCalculator {

    /** Monopoly Deal 标准轨道：颜色键（大写）-> 凑齐 1 套所需张数 */
    public static final Map<String, Integer> REQUIRED_BY_COLOR = Map.ofEntries(
            Map.entry("BROWN", 2),
            Map.entry("LIGHT_BLUE", 3),
            Map.entry("PINK", 3),
            Map.entry("ORANGE", 3),
            Map.entry("RED", 3),
            Map.entry("YELLOW", 3),
            Map.entry("GREEN", 3),
            Map.entry("DARK_BLUE", 2),
            Map.entry("RAILROAD", 4),
            Map.entry("UTILITY", 2)
    );

    private PropertySetCalculator() {
    }

    /**
     * Effective card count for a color (plain + assigned wilds).
     */
    public static int effectiveCountForColor(List<PropertyCard> propertyZone, String colorKey) {
        String key = normalizeColorKey(colorKey);
        if (key == null || propertyZone == null) {
            return 0;
        }
        int solid = 0;
        int wildAssigned = 0;
        for (PropertyCard card : propertyZone) {
            if (card == null) {
                continue;
            }
            if (card.isWildProperty()) {
                if (card instanceof PropertyWildCard w && w.getAssignedColorKey() != null) {
                    if (key.equals(w.getAssignedColorKey())) {
                        wildAssigned++;
                    }
                }
            } else {
                String cg = normalizeColorKey(card.getColorGroup());
                if (key.equals(cg)) {
                    solid++;
                }
            }
        }
        return solid + wildAssigned;
    }

    /**
     * Whether the player has a complete set in this color.
     */
    public static boolean hasCompleteSetForColor(List<PropertyCard> propertyZone, String colorKey) {
        String key = normalizeColorKey(colorKey);
        if (key == null) {
            return false;
        }
        int need = requiredForColor(key);
        if (need <= 0 || need == Integer.MAX_VALUE) {
            return false;
        }
        return effectiveCountForColor(propertyZone, key) >= need;
    }

    /**
     * @return total complete sets across colors; win when >= 3.
     */
    public static int countCompletePropertySets(List<PropertyCard> propertyZone) {
        if (propertyZone == null || propertyZone.isEmpty()) {
            return 0;
        }
        Set<String> colors = new HashSet<>(REQUIRED_BY_COLOR.keySet());
        for (PropertyCard card : propertyZone) {
            if (card == null) {
                continue;
            }
            if (!card.isWildProperty()) {
                String cg = normalizeColorKey(card.getColorGroup());
                if (cg != null) {
                    colors.add(cg);
                }
            } else if (card instanceof PropertyWildCard w && w.getAssignedColorKey() != null) {
                colors.add(w.getAssignedColorKey());
            }
        }

        int total = 0;
        for (String color : colors) {
            int need = requiredForColor(color);
            if (need <= 0 || need == Integer.MAX_VALUE) {
                continue;
            }
            int eff = effectiveCountForColor(propertyZone, color);
            total += eff / need;
        }
        return total;
    }

    static int requiredForColor(String colorKey) {
        if (colorKey == null || colorKey.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return REQUIRED_BY_COLOR.getOrDefault(colorKey, 3);
    }

    private static String normalizeColorKey(String colorGroup) {
        if (colorGroup == null || colorGroup.isBlank()) {
            return null;
        }
        return colorGroup.trim().toUpperCase(Locale.ROOT);
    }
}
