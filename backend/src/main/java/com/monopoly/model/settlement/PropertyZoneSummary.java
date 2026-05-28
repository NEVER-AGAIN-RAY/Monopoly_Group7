package com.monopoly.model.settlement;

import com.monopoly.dto.PropertyColorCount;
import com.monopoly.dto.PropertyColorProgress;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Public property zone summary by color.
 */
public final class PropertyZoneSummary {

    /** Placeholder for unassigned wilds, kept distinct from every real color group. */
    public static final String WILD_UNASSIGNED_COLOR_KEY = "WILD_UNASSIGNED";

    private PropertyZoneSummary() {
    }

    /**
     * Counts cards per color including assigned wilds.
     */
    public static List<PropertyColorCount> summarizeByColor(List<PropertyCard> propertyCards) {
        if (propertyCards == null || propertyCards.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Integer> map = new LinkedHashMap<>();
        for (PropertyCard pc : propertyCards) {
            String key;
            if (pc instanceof PropertyWildCard w) {
                String a = w.getAssignedColorKey();
                if (a == null || a.isBlank()) {
                    key = WILD_UNASSIGNED_COLOR_KEY;
                } else {
                    key = a.trim().toUpperCase(Locale.ROOT);
                }
            } else {
                String cg = pc.getColorGroup();
                if (cg == null || cg.isBlank()) {
                    key = "UNKNOWN";
                } else {
                    key = cg.trim().toUpperCase(Locale.ROOT);
                }
            }
            map.merge(key, 1, Integer::sum);
        }
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        List<PropertyColorCount> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            out.add(new PropertyColorCount(k, map.get(k)));
        }
        return out;
    }

    /**
     * Per-color progress toward a complete set for the UI.
     */
    public static List<PropertyColorProgress> colorProgress(List<PropertyCard> propertyZone) {
        if (propertyZone == null || propertyZone.isEmpty()) {
            return Collections.emptyList();
        }
        List<PropertyColorProgress> out = new ArrayList<>();
        for (String ck : PropertySetCalculator.REQUIRED_BY_COLOR.keySet()) {
            int need = PropertySetCalculator.requiredForColor(ck);
            int eff = PropertySetCalculator.effectiveCountForColor(propertyZone, ck);
            if (eff <= 0) {
                continue;
            }
            int sets = need <= 0 ? 0 : eff / need;
            out.add(new PropertyColorProgress(ck, eff, need, sets));
        }
        int wildUnassigned = 0;
        for (PropertyCard pc : propertyZone) {
            if (pc instanceof PropertyWildCard w
                    && (w.getAssignedColorKey() == null || w.getAssignedColorKey().isBlank())) {
                wildUnassigned++;
            }
        }
        if (wildUnassigned > 0) {
            out.add(new PropertyColorProgress(WILD_UNASSIGNED_COLOR_KEY, wildUnassigned, 0, 0));
        }
        return out;
    }
}
