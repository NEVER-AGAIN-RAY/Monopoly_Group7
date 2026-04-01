package com.monopoly.model.effects;

import com.monopoly.model.PaymentSettlement;
import com.monopoly.model.Player;

/**
 * 债务催缴效果（Debt Collector）：向目标玩家固定收取 5M。
 * requirements：目标若无力支付则本次失败。
 */
public class DebtCollectorEffect implements ActionEffect {

    private static final int DEBT_AMOUNT = 5;

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        Player target = ctx.getTarget();

        if (target == null) {
            return ActionEffectResult.failed("债务催缴需指定目标玩家。");
        }

        PaymentSettlement.Result result = PaymentSettlement.settle(target, actor, DEBT_AMOUNT, ctx.getEngine());
        if (result.isSuccess()) {
            return ActionEffectResult.success(
                    target.getDisplayName() + " 向 " + actor.getDisplayName()
                            + " 支付债务 " + result.getAmountPaid() + "M。");
        } else {
            return ActionEffectResult.failed("债务催缴失败：" + result.getMessage());
        }
    }
}
