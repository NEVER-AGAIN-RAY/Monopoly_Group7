package com.monopoly.model.effects;

import com.monopoly.model.card.Card;
import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.StealTargetZone;

/**
 * 偷牌效果抽象：由 {@link StealTargetZone} 决定从目标 {@link Player} 的财产区或银行堆取牌。
 */
public class StealCardEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        StealTargetZone zone = ctx.getStealTargetZone() != null
                ? ctx.getStealTargetZone()
                : StealTargetZone.PROPERTY;
        if (zone == StealTargetZone.BANK) {
            return stealFromBank(ctx);
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

        boolean removed = target.removePropertyCard(targetProp);
        if (!removed) {
            return ActionEffectResult.failed("状态不一致：无法从目标玩家移除房产。");
        }
        actor.addToPropertyZone(targetProp);
        return ActionEffectResult.success(
                actor.getDisplayName() + " 从 " + target.getDisplayName()
                        + " 偷走了 " + targetProp.getName() + "。");
    }

    private static ActionEffectResult stealFromBank(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        Player target = ctx.getTarget();
        Card card = ctx.getTargetBankCard();

        if (target == null) {
            return ActionEffectResult.failed("偷银行牌需指定目标玩家。");
        }
        if (target.getBankCardCount() == 0) {
            return ActionEffectResult.failed("目标玩家银行没有牌可偷。");
        }
        if (card == null) {
            return ActionEffectResult.failed("偷银行牌需指定目标银行中的一张牌。");
        }
        if (!target.getBankCardsView().contains(card)) {
            return ActionEffectResult.failed("指定卡牌不在目标玩家银行。");
        }

        boolean removed = target.removeFromBank(card);
        if (!removed) {
            return ActionEffectResult.failed("状态不一致：无法从目标银行移除卡牌。");
        }
        actor.receiveCardToHand(card);
        return ActionEffectResult.success(
                actor.getDisplayName() + " 从 " + target.getDisplayName()
                        + " 的银行拿走了 " + card.getName() + "。");
    }
}
