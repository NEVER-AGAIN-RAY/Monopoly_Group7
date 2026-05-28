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
            return ActionEffectResult.failed("强制交换需指定目标玩家。");
        }
        if (actorProp == null || targetProp == null) {
            return ActionEffectResult.failed("强制交换需指定双方各一张房产卡。");
        }
        if (!actor.getPropertyCardsView().contains(actorProp)) {
            return ActionEffectResult.failed("己方指定房产不在财产区。");
        }
        if (!target.getPropertyCardsView().contains(targetProp)) {
            return ActionEffectResult.failed("目标房产不在目标玩家财产区。");
        }

        if (!PropertyStealRules.mayStealPropertyFromTarget(target, targetProp)) {
            return ActionEffectResult.failed("目标房产属于完整套，不能被强制交易。");
        }
        if (!PropertyStealRules.mayStealPropertyFromTarget(actor, actorProp)) {
            return ActionEffectResult.failed("己方指定房产属于完整套，不能被强制交易。");
        }

        if (!actor.removePropertyCard(actorProp)) {
            return ActionEffectResult.failed("状态不一致：无法从己方财产区移除房产。");
        }
        if (!target.removePropertyCard(targetProp)) {
            actor.addToPropertyZone(actorProp);
            return ActionEffectResult.failed("状态不一致：无法从目标玩家财产区移除房产。");
        }
        actor.addToPropertyZone(targetProp);
        target.addToPropertyZone(actorProp);

        return ActionEffectResult.success(
                actor.getDisplayName() + " 与 " + target.getDisplayName()
                        + " Forced property swap：" + actorProp.getName()
                        + " <-> " + targetProp.getName() + "，双方财产区互换。");
    }
}
