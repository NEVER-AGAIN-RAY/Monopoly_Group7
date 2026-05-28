package com.monopoly.model.effects;

import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.RentCalculator;

/** Double rent: base rent plus house/hotel bonus for a color, then multiplied by 2. */
public class DoubleRentEffect implements ActionEffect {

    public static RentEffect.DueResult computeDue(ActionEffectContext ctx) {
        Player landlord = ctx.getActor();
        Player tenant = ctx.getTarget();
        String colorKey = ctx.getTargetColorKey();

        if (landlord == null) {
            return RentEffect.DueResult.error("房东无效。");
        }
        if (tenant == null) {
            return RentEffect.DueResult.error("Double rent multiplier需指定目标玩家。");
        }
        if (colorKey == null || colorKey.isBlank()) {
            return RentEffect.DueResult.error("Double rent multiplier需指定颜色。");
        }

        String ck = colorKey.trim().toUpperCase(java.util.Locale.ROOT);
        int base = RentCalculator.computeRentForColor(landlord, ck);
        int due = base * 2;
        if (due <= 0) {
            return RentEffect.DueResult.error("你在财产区没有 " + ck + " 房产，无法双倍收租。");
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
                    "Double rent multiplier成功：" + tenant.getDisplayName() + " 支付 " + result.getAmountPaid()
                            + "M（基础 " + base + "M × 2）。");
        } else {
            return ActionEffectResult.failed("Double rent multiplier失败：" + result.getMessage());
        }
    }
}
