package com.monopoly.model.effects;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.BuildingPlacementRules;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.card.PropertyWildCard;

import java.util.Locale;

/**
 * House: add house on a complete set property (no house/hotel yet).
 */
public final class HouseEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        PropertyCard target = ctx.getActorProperty();
        if (actor == null) {
            return ActionEffectResult.failed("Missing acting player.");
        }
        if (target == null) {
            return ActionEffectResult.failed("Specify the property in your property zone to add a house to (actorCardId or targetCardId pointing to your own property).");
        }
        if (!actor.getPropertyCardsView().contains(target)) {
            return ActionEffectResult.failed("The specified property is not in your property zone.");
        }
        String colorKey = resolveColorKey(target);
        if (colorKey == null) {
            return ActionEffectResult.failed("A wild property must be assigned a color before a house can be added.");
        }
        if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), colorKey)) {
            return ActionEffectResult.failed("This color does not form a complete property set; cannot add a house.");
        }
        if (!BuildingPlacementRules.allowsHouseHotel(colorKey)) {
            return ActionEffectResult.failed("Railroad and utility sets cannot have houses or hotels.");
        }
        if (BuildingPlacementRules.hasAnyBuildingForColor(actor.getPropertyCardsView(), colorKey)) {
            return ActionEffectResult.failed("This complete set already has a house or hotel.");
        }
        if (target.getBuildingLevel() != BuildingLevel.BASE) {
            return ActionEffectResult.failed("This property already has a building; use a hotel card to upgrade the house to a hotel.");
        }
        target.setBuildingLevel(BuildingLevel.HOUSE);
        return ActionEffectResult.success("Added a house to the property.");
    }

    static String resolveColorKey(PropertyCard card) {
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
}
