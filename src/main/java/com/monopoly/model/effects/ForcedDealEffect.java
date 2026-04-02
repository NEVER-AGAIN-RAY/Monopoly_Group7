package com.monopoly.model.effects;

import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;

/**
 * 强制交换效果（Forced Deal）：以施效者财产区一张房产，强制换取目标玩家财产区一张房产。
 * requirements：目标需有已部署的房产。
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

        actor.removePropertyCard(actorProp);
        target.removePropertyCard(targetProp);
        actor.addToPropertyZone(targetProp);
        target.addToPropertyZone(actorProp);

        return ActionEffectResult.success(
                actor.getDisplayName() + " 与 " + target.getDisplayName()
                        + " 强制交换房产：" + actorProp.getName() + " <-> " + targetProp.getName() + "。");
    }
}
