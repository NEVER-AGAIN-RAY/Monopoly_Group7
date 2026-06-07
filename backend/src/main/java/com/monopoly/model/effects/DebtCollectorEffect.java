package com.monopoly.model.effects;

import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;

/**
 * Debt Collector: charge target exactly 5M or fail if cannot pay.
 */
public class DebtCollectorEffect implements ActionEffect {

    private static final int DEBT_AMOUNT = 5;

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        Player target = ctx.getTarget();

        if (target == null) {
            return ActionEffectResult.failed("Debt Collector requires a target player.");
        }

        PaymentSettlement.Result result = PaymentSettlement.settle(target, actor, DEBT_AMOUNT, ctx.getEngine());
        if (result.isSuccess()) {
            return ActionEffectResult.success(
                    target.getDisplayName() + " paid " + actor.getDisplayName()
                            + " a debt of " + result.getAmountPaid() + "M.");
        } else {
            return ActionEffectResult.failed("Debt collection failed: " + result.getMessage());
        }
    }
}
