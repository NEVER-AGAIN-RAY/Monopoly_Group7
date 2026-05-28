package com.monopoly.model.settlement;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Complete set counting for win condition and building rules.
 */
public final class PropertySetCalculator {

    public static final List<String> STANDARD_COLOR_ORDER = List.of(
            "BROWN",
            "LIGHT_BLUE",
            "PINK",
            "ORANGE",
            "RED",
            "YELLOW",
            "GREEN",
            "DARK_BLUE",
            "RAILROAD",
            "UTILITY"
    );

    /** Monopoly Deal 标准轨道：颜色键（大写）-> 凑齐 1 套所需张数 */
    public static final Map<String, Integer> REQUIRED_BY_COLOR = requiredByColor();

    private PropertySetCalculator() {
    }

    private static Map<String, Integer> requiredByColor() {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        out.put("BROWN", 2);
        out.put("LIGHT_BLUE", 3);
        out.put("PINK", 3);
        out.put("ORANGE", 3);
        out.put("RED", 3);
        out.put("YELLOW", 3);
        out.put("GREEN", 3);
        out.put("DARK_BLUE", 2);
        out.put("RAILROAD", 4);
        out.put("UTILITY", 2);
        return Collections.unmodifiableMap(out);
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
        Set<String> colors = new LinkedHashSet<>(REQUIRED_BY_COLOR.keySet());
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
