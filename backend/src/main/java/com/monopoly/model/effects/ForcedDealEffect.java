package com.monopoly.model.effects;

import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.PropertyStealRules;

/**
 * Forced Deal: swap one property with target (target must have a property).
 */
public class ForcedDealEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        Player target = ctx.getTarget();
        PropertyCard actorProp = ctx.getActorProperty();
        PropertyCard targetProp = ctx.getTargetProperty();

        if (target == null) {
            return ActionEffectResult.failed("Forced Deal requires a target player.");
        }
        if (actorProp == null || targetProp == null) {
            return ActionEffectResult.failed("Forced Deal requires one property card from each side.");
        }
        if (!actor.getPropertyCardsView().contains(actorProp)) {
            return ActionEffectResult.failed("Your specified property is not in your property zone.");
        }
        if (!target.getPropertyCardsView().contains(targetProp)) {
            return ActionEffectResult.failed("The target property is not in the target player's property zone.");
        }

        if (!PropertyStealRules.mayStealPropertyFromTarget(target, targetProp)) {
            return ActionEffectResult.failed("The target property belongs to a complete set and cannot be force-traded.");
        }
        if (!PropertyStealRules.mayStealPropertyFromTarget(actor, actorProp)) {
            return ActionEffectResult.failed("Your specified property belongs to a complete set and cannot be force-traded.");
        }

        if (!actor.removePropertyCard(actorProp)) {
            return ActionEffectResult.failed("Inconsistent state: failed to remove the property from your property zone.");
        }
        if (!target.removePropertyCard(targetProp)) {
            actor.addToPropertyZone(actorProp);
            return ActionEffectResult.failed("Inconsistent state: failed to remove the property from the target player's property zone.");
        }
        actor.addToPropertyZone(targetProp);
        target.addToPropertyZone(actorProp);

        return ActionEffectResult.success(
                actor.getDisplayName() + " and " + target.getDisplayName()
                        + " forced property swap: " + actorProp.getName()
                        + " <-> " + targetProp.getName() + " exchanged between property zones.");
    }
}
