package com.monopoly.model.effects;

import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.PropertyStealRules;
import com.monopoly.model.settlement.StealTargetZone;

/**
 * Steal (Sly Deal): pick property or bank card via StealTargetZone.
 */
public class StealCardEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        StealTargetZone zone = ctx.getStealTargetZone() != null
                ? ctx.getStealTargetZone()
                : StealTargetZone.PROPERTY;
        if (zone == StealTargetZone.BANK) {
            return ActionEffectResult.failed("Sly Deal can only steal one property from the target player's property zone.");
        }
        return stealFromPropertyZone(ctx);
    }

    private static ActionEffectResult stealFromPropertyZone(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        Player target = ctx.getTarget();
        PropertyCard targetProp = ctx.getTargetProperty();

        if (target == null) {
            return ActionEffectResult.failed("A target player must be specified to steal a property.");
        }
        if (target.getPropertyCardCount() == 0) {
            return ActionEffectResult.failed("The target player has no deployed property; nothing to steal.");
        }
        if (targetProp == null) {
            return ActionEffectResult.failed("A property card to steal must be specified.");
        }
        if (!target.getPropertyCardsView().contains(targetProp)) {
            return ActionEffectResult.failed("The specified property is not in the target player's property zone.");
        }
        if (!PropertyStealRules.mayStealPropertyFromTarget(target, targetProp)) {
            return ActionEffectResult.failed("The specified property belongs to a complete set and cannot be stolen by Sly Deal.");
        }

        boolean removed = target.removePropertyCard(targetProp);
        if (!removed) {
            return ActionEffectResult.failed("Inconsistent state: failed to remove the property from the target player.");
        }
        actor.addToPropertyZone(targetProp);
        return ActionEffectResult.success(
                actor.getDisplayName() + " stole " + targetProp.getName() + " from "
                        + target.getDisplayName() + " into the property zone.");
    }

}
