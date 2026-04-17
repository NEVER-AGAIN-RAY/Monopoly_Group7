package com.monopoly.model.settlement;

import java.util.Locale;

/**
 * 房屋 / 旅馆能否加在某一颜色轨道上（实体规则：铁路、公共事业不可加盖）。
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
