package com.monopoly.model.settlement;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 计算玩家财产区中「完整地产集」数量及各色有效张数。
 * <p>
 * 纯色牌张数 + 已挂载为指定颜色的万能牌张数 ≥ 该颜色凑套需求数时，视为该色至少有一套完整集（用于胜利、盖房等）；
 * 收租按张累计见 {@link RentCalculator}。完整集总套数按各色分别取 {@code floor(有效张数 / 需求张数)} 后求和。
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
     * 指定颜色的「纯色 + 已声明为该色的万能牌」有效张数。
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
     * 该颜色是否至少有一套完整地产集（胜利计数、加盖房屋、交易破坏者等；收租见 {@link RentCalculator}）。
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
     * @return 各色完整套数之和：对每种出现过的颜色取 {@code floor(有效张数 / 需求张数)} 后累加。
     *         <p>本项目的<strong>胜利条件</strong>（与 Hasbro 常见规则一致）为：该值 {@code >= 3}（可跨颜色），
     *         与需求文档中「同色」字面的歧义说明见 {@code docs/REQ_TRACE.md}。
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
