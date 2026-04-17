package com.monopoly.model.effects;

import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.RentCalculator;

/**
 * 双倍租金效果：须该色完整套；应付 = 该色应收基础租与建筑加值之和 × 2。
 */
public class DoubleRentEffect implements ActionEffect {

    public static RentEffect.DueResult computeDue(ActionEffectContext ctx) {
        Player landlord = ctx.getActor();
        Player tenant = ctx.getTarget();
        String colorKey = ctx.getTargetColorKey();

        if (tenant == null) {
            return RentEffect.DueResult.error("双倍租金需指定目标玩家。");
        }
        if (colorKey == null || colorKey.isBlank()) {
            return RentEffect.DueResult.error("双倍租金需指定颜色。");
        }

        String ck = colorKey.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PropertySetCalculator.hasCompleteSetForColor(landlord.getPropertyCardsView(), ck)) {
            return RentEffect.DueResult.error("你在财产区没有 " + ck + " 的完整房产套，无法打出双倍租金牌。");
        }

        int base = RentCalculator.computeRentForColor(landlord, colorKey);
        int due = base * 2;
        if (due <= 0) {
            return RentEffect.DueResult.error("该颜色租金为 0，无法双倍收租。");
        }
        return RentEffect.DueResult.ok(due);
    }

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        RentEffect.DueResult dueResult = computeDue(ctx);
        if (!dueResult.isOk()) {
            return ActionEffectResult.failed(dueResult.getError());
        }
        int due = dueResult.getAmountDue();
        Player landlord = ctx.getActor();
        Player tenant = ctx.getTarget();

        PaymentSettlement.Result result = PaymentSettlement.settle(tenant, landlord, due, ctx.getEngine());
        if (result.isSuccess()) {
            int base = due / 2;
            return ActionEffectResult.success(
                    "双倍租金成功：" + tenant.getDisplayName() + " 支付 " + result.getAmountPaid()
                            + "M（基础 " + base + "M × 2）。");
        } else {
            return ActionEffectResult.failed("双倍租金失败：" + result.getMessage());
        }
    }
}
