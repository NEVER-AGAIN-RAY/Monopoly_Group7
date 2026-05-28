package com.monopoly.model.settlement;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.Player;

import java.util.Locale;

/**
 * Cannot steal from an opponent's complete color set.
 */
public final class PropertyStealRules {

    private PropertyStealRules() {
    }

    public static boolean mayStealPropertyFromTarget(Player target, PropertyCard property) {
        if (target == null || property == null) {
            return false;
        }
        String ck = colorKeyForSteal(property);
        if (ck == null) {
            return true;
        }
        return !PropertySetCalculator.hasCompleteSetForColor(target.getPropertyCardsView(), ck);
    }

    private static String colorKeyForSteal(PropertyCard p) {
        if (p.isWildProperty() && p instanceof PropertyWildCard w) {
            String a = w.getAssignedColorKey();
            if (a == null || a.isBlank()) {
                return null;
            }
            return a.trim().toUpperCase(Locale.ROOT);
        }
        String cg = p.getColorGroup();
        if (cg == null || cg.isBlank()) {
            return null;
        }
        return cg.trim().toUpperCase(Locale.ROOT);
    }
}
