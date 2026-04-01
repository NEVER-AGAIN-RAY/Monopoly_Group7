package com.monopoly.model.effects;

import com.monopoly.model.Player;
import com.monopoly.model.PropertyCard;
import com.monopoly.model.PropertySetCalculator;
import com.monopoly.model.PropertyWildCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deal Breaker：夺取目标玩家的一整套完整颜色地产。
 */
public final class DealBreakerEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        Player target = ctx.getTarget();
        if (target == null) {
            return ActionEffectResult.failed("Deal Breaker 需指定目标玩家。");
        }

        String colorKey = resolveTargetColorKey(ctx);
        if (colorKey == null) {
            return ActionEffectResult.failed("Deal Breaker 需指定目标完整套颜色。");
        }
        if (!PropertySetCalculator.hasCompleteSetForColor(target.getPropertyCardsView(), colorKey)) {
            return ActionEffectResult.failed("目标玩家不存在 " + colorKey + " 的完整房产集。");
        }

        List<PropertyCard> stealSet = collectSetCards(target, colorKey);
        if (stealSet.isEmpty()) {
            return ActionEffectResult.failed("未找到可夺取的完整套。");
        }
        for (PropertyCard card : stealSet) {
            if (target.removePropertyCard(card)) {
                actor.addToPropertyZone(card);
            }
        }
        return ActionEffectResult.success(
                actor.getDisplayName() + " 夺取了 " + target.getDisplayName() + " 的完整套：" + colorKey + "。");
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
