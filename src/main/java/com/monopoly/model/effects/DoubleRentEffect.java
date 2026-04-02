package com.monopoly.model.effects;

import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.RentCalculator;

/**
 * 双倍租金效果（Double The Rent）：配合下一张收租牌使用时租金翻倍。
 * 简化实现：直接按当前颜色完整套数租金 × 2 向目标收取。
 * requirements：无完整套数时无法打出。
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

        if (!PropertySetCalculator.hasCompleteSetForColor(landlord.getPropertyCardsView(), colorKey)) {
            return RentEffect.DueResult.error("无 " + colorKey + " 完整集，无法打出双倍租金牌。");
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
