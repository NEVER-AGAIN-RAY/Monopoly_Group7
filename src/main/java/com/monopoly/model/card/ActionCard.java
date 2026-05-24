package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.rules.MonopolyDealBankValues;
import com.monopoly.model.settlement.BuildingPlacementRules;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.PropertyStealRules;
import com.monopoly.model.settlement.StealTargetZone;
import com.monopoly.model.player.Player;

import java.util.List;
import java.util.Locale;

/**
 * Action card with effectCode; resolved by controller stack.
 */
public class ActionCard extends Card implements Payable {

    private final String effectCode;
    private final int bankValueM;
    /** 仅 RENT_DUAL：卡面两色键，其余类型为空列表。 */
    private final List<String> rentPalette;
    /**
     * RENT_DUAL flag: charge every opponent when true.
     */
    private final boolean rentDualChargesEachOtherPlayer;
    /** 仅 RENT：卡面为「任意色」租金牌（展示用），规则同单色收租。 */
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
     * Bank value when deposited (M).
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

        if ("RENT".equals(code)) {
            String color = (params != null && params.getTargetColorKey() != null)
                    ? params.getTargetColorKey().trim()
                    : "";
            if (color.isEmpty()) {
                return false;
            }
            return PropertySetCalculator.effectiveCountForColor(actor.getPropertyCardsView(), color) > 0;
        }

        if ("DOUBLE_RENT".equals(code)) {
            return !context.hasPendingDoubleRentFor(actor.getPlayerId()) && hasAnyRentableProperty(actor);
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
            if (PropertySetCalculator.effectiveCountForColor(actor.getPropertyCardsView(), chosen) <= 0) {
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
            if (target == null || target == actor
                    || target.getPropertyCardCount() == 0
                    || actor.getPropertyCardCount() == 0) {
                return false;
            }
            String targetCardId = params.getTargetCardId();
            String actorCardId = params.getActorCardId();
            if (blank(targetCardId) && blank(actorCardId)) {
                return hasTradableProperty(target) && hasTradableProperty(actor);
            }
            if (blank(targetCardId) || blank(actorCardId)) {
                return false;
            }
            PropertyCard targetProperty = findPropertyById(target, targetCardId);
            PropertyCard actorProperty = findPropertyById(actor, actorCardId);
            return targetProperty != null
                    && actorProperty != null
                    && PropertyStealRules.mayStealPropertyFromTarget(target, targetProperty)
                    && PropertyStealRules.mayStealPropertyFromTarget(actor, actorProperty);
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
            if (params == null || blank(params.getTargetPlayerId())) {
                return false;
            }
            Player target = context.findPlayer(params.getTargetPlayerId());
            if (target == null || target == actor) {
                return false;
            }
            String colorKey = normalizeColorKey(params.getTargetColorKey());
            if (colorKey == null && !blank(params.getTargetCardId())) {
                PropertyCard targetProperty = findPropertyById(target, params.getTargetCardId());
                colorKey = colorKeyForProperty(targetProperty);
            }
            return colorKey != null
                    && PropertySetCalculator.hasCompleteSetForColor(target.getPropertyCardsView(), colorKey);
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

    private static boolean hasAnyRentableProperty(Player actor) {
        for (String color : PropertySetCalculator.REQUIRED_BY_COLOR.keySet()) {
            if (PropertySetCalculator.effectiveCountForColor(actor.getPropertyCardsView(), color) > 0) {
                return true;
            }
        }
        return false;
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
        if (BuildingPlacementRules.hasAnyBuildingForColor(actor.getPropertyCardsView(), key)) {
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
        if (BuildingPlacementRules.hasHotelForColor(actor.getPropertyCardsView(), key)) {
            return false;
        }
        return pc.getBuildingLevel() == BuildingLevel.HOUSE;
    }

    private static String resolveColorKeyForUpgrade(PropertyCard card) {
        return colorKeyForProperty(card);
    }

    private static String colorKeyForProperty(PropertyCard card) {
        if (card == null) {
            return null;
        }
        if (card.isWildProperty() && card instanceof PropertyWildCard w) {
            return normalizeColorKey(w.getAssignedColorKey());
        }
        return normalizeColorKey(card.getColorGroup());
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
            return false;
        }
        String targetCardId = params.getTargetCardId();
        if (targetCardId == null || targetCardId.isBlank()) {
            for (PropertyCard property : target.getPropertyCardsView()) {
                if (PropertyStealRules.mayStealPropertyFromTarget(target, property)) {
                    return true;
                }
            }
            return false;
        }
        for (PropertyCard property : target.getPropertyCardsView()) {
            if (targetCardId.equals(property.getId())) {
                return PropertyStealRules.mayStealPropertyFromTarget(target, property);
            }
        }
        return false;
    }

    private static boolean hasTradableProperty(Player owner) {
        if (owner == null) {
            return false;
        }
        for (PropertyCard property : owner.getPropertyCardsView()) {
            if (PropertyStealRules.mayStealPropertyFromTarget(owner, property)) {
                return true;
            }
        }
        return false;
    }

    private static PropertyCard findPropertyById(Player owner, String propertyCardId) {
        if (owner == null || blank(propertyCardId)) {
            return null;
        }
        for (PropertyCard property : owner.getPropertyCardsView()) {
            if (property != null && propertyCardId.equals(property.getId())) {
                return property;
            }
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String normalizeColorKey(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            return null;
        }
        return colorKey.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public int getPaymentValue() {
        return bankValueM;
    }
}
