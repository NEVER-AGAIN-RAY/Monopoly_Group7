package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.rules.MonopolyDealBankValues;
import com.monopoly.model.settlement.BuildingPlacementRules;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.StealTargetZone;
import com.monopoly.model.player.Player;

import java.util.List;
import java.util.Locale;

/**
 * 行动卡：打出后触发特殊效果（收租、偷牌等），效果链由控制器/引擎调度。
 */
public class ActionCard extends Card implements Payable {

    private final String effectCode;
    private final int bankValueM;
    /** 仅 {@code RENT_DUAL}：卡面两色键，其余类型为空列表。 */
    private final List<String> rentPalette;
    /**
     * 仅 {@code RENT_DUAL}：true 时对除房东外每名玩家依次收租（房规/扩展）；实体默认 false（1 对 1）。
     */
    private final boolean rentDualChargesEachOtherPlayer;
    /** 仅 {@code RENT}：卡面为「任意色」租金牌（展示用），规则同单色收租。 */
    private final boolean wildcardRentCard;

    public ActionCard(String id, String name, String effectCode) {
        this(id, name, effectCode, List.of());
    }

    public ActionCard(String id, String name, String effectCode, List<String> rentPalette) {
        this(id, name, effectCode, MonopolyDealBankValues.bankValueForActionEffect(effectCode), rentPalette);
    }

    public ActionCard(String id, String name, String effectCode, int bankValueM) {
        this(id, name, effectCode, bankValueM, List.of());
    }

    public ActionCard(String id, String name, String effectCode, int bankValueM, List<String> rentPalette) {
        this(id, name, effectCode, bankValueM, rentPalette, false, false);
    }

    public ActionCard(
            String id,
            String name,
            String effectCode,
            int bankValueM,
            List<String> rentPalette,
            boolean rentDualChargesEachOtherPlayer,
            boolean wildcardRentCard) {
        super(id, name);
        this.effectCode = effectCode;
        this.bankValueM = Math.max(0, bankValueM);
        this.rentPalette = rentPalette == null ? List.of() : List.copyOf(rentPalette);
        this.rentDualChargesEachOtherPlayer = rentDualChargesEachOtherPlayer;
        this.wildcardRentCard = wildcardRentCard;
    }

    /** 不可变；非双色收租牌为空列表。 */
    public List<String> getRentPaletteView() {
        return rentPalette;
    }

    public boolean isRentDualChargesEachOtherPlayer() {
        return rentDualChargesEachOtherPlayer;
    }

    public boolean isWildcardRentCard() {
        return wildcardRentCard;
    }

    /**
     * 存入银行时可作现金的 M 数（实体牌角标）。
     */
    public int getBankValueM() {
        return bankValueM;
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

        if ("RENT_DUAL".equals(code)) {
            if (params == null || blank(params.getTargetColorKey())) {
                return false;
            }
            String chosen = params.getTargetColorKey().trim().toUpperCase(Locale.ROOT);
            boolean inPalette = rentPalette.stream().anyMatch(chosen::equals);
            if (!inPalette) {
                return false;
            }
            if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), chosen)) {
                return false;
            }
            if (!rentDualChargesEachOtherPlayer) {
                if (blank(params.getTargetPlayerId())) {
                    return false;
                }
                Player target = context.findPlayer(params.getTargetPlayerId());
                return target != null && target != actor;
            }
            return true;
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
        if (!BuildingPlacementRules.allowsHouseHotel(key)) {
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
        if (!BuildingPlacementRules.allowsHouseHotel(key)) {
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
        return bankValueM;
    }
}
