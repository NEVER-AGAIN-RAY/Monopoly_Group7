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
            return ActionEffectResult.failed("Sly Deal 只能偷取目标玩家财产区的一张房产。");
        }
        return stealFromPropertyZone(ctx);
    }

    private static ActionEffectResult stealFromPropertyZone(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        Player target = ctx.getTarget();
        PropertyCard targetProp = ctx.getTargetProperty();

        if (target == null) {
            return ActionEffectResult.failed("偷财产需指定目标玩家。");
        }
        if (target.getPropertyCardCount() == 0) {
            return ActionEffectResult.failed("目标玩家没有已部署的房产，无法偷财产。");
        }
        if (targetProp == null) {
            return ActionEffectResult.failed("偷财产需指定要偷的房产卡。");
        }
        if (!target.getPropertyCardsView().contains(targetProp)) {
            return ActionEffectResult.failed("指定房产不在目标玩家财产区。");
        }
        if (!PropertyStealRules.mayStealPropertyFromTarget(target, targetProp)) {
            return ActionEffectResult.failed("指定房产属于完整套，不能被暗中交易偷走。");
        }

        boolean removed = target.removePropertyCard(targetProp);
        if (!removed) {
            return ActionEffectResult.failed("状态不一致：无法从目标玩家移除房产。");
        }
        actor.addToPropertyZone(targetProp);
        return ActionEffectResult.success(
                actor.getDisplayName() + " 从 " + target.getDisplayName()
                        + " 偷走了 " + targetProp.getName() + "，收入财产区。");
    }

}
