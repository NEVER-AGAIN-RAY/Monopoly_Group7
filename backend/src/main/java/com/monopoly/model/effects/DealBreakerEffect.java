package com.monopoly.model.effects;

import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.card.PropertyWildCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deal Breaker: steal one complete color set from a target.
 */
public final class DealBreakerEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        Player target = ctx.getTarget();
        if (target == null) {
            return ActionEffectResult.failed("Deal Breaker requires a target player.");
        }

        String colorKey = resolveTargetColorKey(ctx);
        if (colorKey == null) {
            return ActionEffectResult.failed("Deal Breaker requires the color of a complete set to target.");
        }
        if (!PropertySetCalculator.hasCompleteSetForColor(target.getPropertyCardsView(), colorKey)) {
            return ActionEffectResult.failed("The target player has no complete " + colorKey + " property set.");
        }

        List<PropertyCard> stealSet = collectSetCards(target, colorKey);
        if (stealSet.isEmpty()) {
            return ActionEffectResult.failed("No complete set available to steal.");
        }
        for (PropertyCard card : stealSet) {
            if (target.removePropertyCard(card)) {
                actor.addToPropertyZone(card);
            }
        }
        return ActionEffectResult.success(
                actor.getDisplayName() + " stole " + target.getDisplayName()
                        + "'s complete " + colorKey + " set into the property zone.");
    }

    private static String resolveTargetColorKey(ActionEffectContext ctx) {
        String colorKey = normalize(ctx.getTargetColorKey());
        if (colorKey != null) {
            return colorKey;
        }
        PropertyCard selected = ctx.getTargetProperty();
        if (selected == null) {
            return null;
        }
        if (selected.isWildProperty() && selected instanceof PropertyWildCard wild) {
            return normalize(wild.getAssignedColorKey());
        }
        return normalize(selected.getColorGroup());
    }

    private static List<PropertyCard> collectSetCards(Player target, String colorKey) {
        List<PropertyCard> cards = new ArrayList<>();
        for (PropertyCard card : target.getPropertyCardsView()) {
            if (card == null) {
                continue;
            }
            if (!card.isWildProperty()) {
                if (colorKey.equals(normalize(card.getColorGroup()))) {
                    cards.add(card);
                }
                continue;
            }
            if (card instanceof PropertyWildCard wild && colorKey.equals(normalize(wild.getAssignedColorKey()))) {
                cards.add(card);
            }
        }
        return cards;
    }

    private static String normalize(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            return null;
        }
        return colorKey.trim().toUpperCase(Locale.ROOT);
    }
}
