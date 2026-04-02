package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.StealTargetZone;
import com.monopoly.model.player.Player;

import java.util.Locale;

/**
 * 行动卡：打出后触发特殊效果（收租、偷牌等），效果链由控制器/引擎调度。
 */
public class ActionCard extends Card implements Payable {

    private final String effectCode;

    public ActionCard(String id, String name, String effectCode) {
        super(id, name);
        this.effectCode = effectCode;
    }

    public String getEffectCode() {
        return effectCode;
    }

    @Override
    public boolean canPlay(Player actor, ActionParamContext params, GameContext context) {
        if (actor == null) {
            return false;
        }
        if (context == null) {
            context = new GameContext();
        }
        String code = effectCode == null ? "" : effectCode.trim().toUpperCase();

        if ("RENT".equals(code) || "DOUBLE_RENT".equals(code)) {
            String color = (params != null && params.getTargetColorKey() != null)
                    ? params.getTargetColorKey().trim()
                    : "";
            if (color.isEmpty()) {
                return false;
            }
            return PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), color);
        }

        if ("STEAL_PROPERTY".equals(code)) {
            return canPlaySteal(actor, params, context);
        }

        if ("FORCED_DEAL".equals(code)) {
            if (params == null || blank(params.getTargetPlayerId())) {
                return false;
            }
            Player target = context.findPlayer(params.getTargetPlayerId());
            return target != null && target != actor
                    && target.getPropertyCardCount() > 0
                    && actor.getPropertyCardCount() > 0;
        }

        if ("DEBT_COLLECTOR".equals(code)) {
            if (params == null || blank(params.getTargetPlayerId())) {
                return false;
            }
            Player target = context.findPlayer(params.getTargetPlayerId());
            if (target == null || target == actor) {
                return false;
            }
            return target.totalBankValueM() > 0 || target.getPropertyCardCount() > 0;
        }

        if ("RENT_WAIVER".equals(code)) {
            if (context == null || context.getResponseState() == null) {
                return false;
            }
            return context.isAwaitingResponseFrom(actor.getPlayerId());
        }

        if ("PASS_GO".equals(code)) {
            return true;
        }

        if ("HOUSE".equals(code)) {
            PropertyCard pc = findActorPropertyForUpgrade(actor, params);
            return pc != null && canPlayHouse(actor, pc);
        }

        if ("HOTEL".equals(code)) {
            PropertyCard pc = findActorPropertyForUpgrade(actor, params);
            return pc != null && canPlayHotel(actor, pc);
        }

        if ("BIRTHDAY".equals(code)) {
            return context != null && context.getPlayers() != null && context.getPlayers().size() >= 2;
        }

        if ("DEAL_BREAKER".equals(code)) {
            return true;
        }

        return true;
    }

    private static PropertyCard findActorPropertyForUpgrade(Player actor, ActionParamContext params) {
        if (actor == null || params == null) {
            return null;
        }
        String id = params.getActorCardId();
        if (id == null || id.isBlank()) {
            id = params.getTargetCardId();
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        for (PropertyCard p : actor.getPropertyCardsView()) {
            if (p != null && id.equals(p.getId())) {
                return p;
            }
        }
        return null;
    }

    private static boolean canPlayHouse(Player actor, PropertyCard pc) {
        String key = resolveColorKeyForUpgrade(pc);
        if (key == null) {
            return false;
        }
        if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), key)) {
            return false;
        }
        return pc.getBuildingLevel() == BuildingLevel.BASE;
    }

    private static boolean canPlayHotel(Player actor, PropertyCard pc) {
        String key = resolveColorKeyForUpgrade(pc);
        if (key == null) {
            return false;
        }
        if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), key)) {
            return false;
        }
        return pc.getBuildingLevel() == BuildingLevel.HOUSE;
    }

    private static String resolveColorKeyForUpgrade(PropertyCard card) {
        if (card == null) {
            return null;
        }
        if (card.isWildProperty() && card instanceof PropertyWildCard w) {
            String a = w.getAssignedColorKey();
            return a == null || a.isBlank() ? null : a.trim().toUpperCase(Locale.ROOT);
        }
        String cg = card.getColorGroup();
        if (cg == null || cg.isBlank()) {
            return null;
        }
        return cg.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean canPlaySteal(Player actor, ActionParamContext params, GameContext context) {
        if (params == null || blank(params.getTargetPlayerId())) {
            return false;
        }
        Player target = context.findPlayer(params.getTargetPlayerId());
        if (target == null || target == actor) {
            return false;
        }
        StealTargetZone zone = StealTargetZone.fromParam(params != null ? params.getTargetZone() : null);
        if (zone == StealTargetZone.BANK) {
            return target.getBankCardCount() > 0;
        }
        return target.getPropertyCardCount() > 0;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public int getPaymentValue() {
        return 3;
    }
}
