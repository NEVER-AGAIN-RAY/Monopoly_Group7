package com.monopoly.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 计算玩家财产区中「完整地产集」数量（含万能房产牌分配）。
 * <p>
 * 每种颜色有固定凑齐一套所需的张数（见 {@link #REQUIRED_BY_COLOR}）；非标准颜色名（如工厂占位
 * {@code UNKNOWN_COLOR}）按 3 张一套处理。
 * 万能牌每轮补到「距离凑齐还差张数最少」的颜色（贪心），以尽量多出完整集。
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
     * @return 当前已完成的完整地产集套数（可 &gt; 1；胜利条件通常为 &gt;= 3）
     */
    public static int countCompletePropertySets(List<PropertyCard> propertyZone) {
        if (propertyZone == null || propertyZone.isEmpty()) {
            return 0;
        }
        int wilds = 0;
        Map<String, Integer> fixed = new HashMap<>();
        for (PropertyCard card : propertyZone) {
            if (card == null) {
                continue;
            }
            if (card.isWildProperty()) {
                wilds++;
                continue;
            }
            String key = normalizeColorKey(card.getColorGroup());
            if (key == null) {
                continue;
            }
            fixed.merge(key, 1, Integer::sum);
        }

        Set<String> allColors = new HashSet<>(REQUIRED_BY_COLOR.keySet());
        allColors.addAll(fixed.keySet());

        Map<String, Integer> effective = new HashMap<>(fixed);
        for (String c : allColors) {
            effective.putIfAbsent(c, 0);
        }

        for (int i = 0; i < wilds; i++) {
            String best = null;
            int bestGap = Integer.MAX_VALUE;
            for (String color : allColors) {
                int need = requiredForColor(color);
                int eff = effective.getOrDefault(color, 0);
                if (eff >= need) {
                    continue;
                }
                int gap = need - eff;
                if (gap < bestGap) {
                    bestGap = gap;
                    best = color;
                }
            }
            if (best == null) {
                break;
            }
            effective.merge(best, 1, Integer::sum);
        }

        int complete = 0;
        for (String color : allColors) {
            int need = requiredForColor(color);
            if (need <= 0 || need == Integer.MAX_VALUE) {
                continue;
            }
            int eff = effective.getOrDefault(color, 0);
            complete += eff / need;
        }
        return complete;
    }

    private static int requiredForColor(String colorKey) {
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
