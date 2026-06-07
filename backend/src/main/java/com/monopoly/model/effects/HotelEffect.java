package com.monopoly.model.effects;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.BuildingPlacementRules;
import com.monopoly.model.settlement.PropertySetCalculator;

/**
 * Hotel: upgrade a house to hotel on same property.
 */
public final class HotelEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        PropertyCard target = ctx.getActorProperty();
        if (actor == null) {
            return ActionEffectResult.failed("Missing acting player.");
        }
        if (target == null) {
            return ActionEffectResult.failed("Specify the property in your property zone to upgrade to a hotel (actorCardId or targetCardId).");
        }
        if (!actor.getPropertyCardsView().contains(target)) {
            return ActionEffectResult.failed("The specified property is not in your property zone.");
        }
        String colorKey = HouseEffect.resolveColorKey(target);
        if (colorKey == null) {
            return ActionEffectResult.failed("A wild property must be assigned a color first.");
        }
        if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), colorKey)) {
            return ActionEffectResult.failed("This color does not form a complete set; cannot upgrade to a hotel.");
        }
        if (!BuildingPlacementRules.allowsHouseHotel(colorKey)) {
            return ActionEffectResult.failed("Railroad and utility sets cannot have hotels.");
        }
        if (BuildingPlacementRules.hasHotelForColor(actor.getPropertyCardsView(), colorKey)) {
            return ActionEffectResult.failed("This complete set already has a hotel.");
        }
        if (target.getBuildingLevel() != BuildingLevel.HOUSE) {
            return ActionEffectResult.failed("A house must be added to this property before it can be upgraded to a hotel.");
        }
        target.setBuildingLevel(BuildingLevel.HOTEL);
        return ActionEffectResult.success("Upgraded the house to a hotel.");
    }
}
