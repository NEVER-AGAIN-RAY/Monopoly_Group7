package com.monopoly.model.effects;

import com.monopoly.model.PaymentSettlement;
import com.monopoly.model.Player;
import com.monopoly.model.PropertySetCalculator;
import com.monopoly.model.RentCalculator;

/**
 * 收租效果：向目标玩家按施效者指定颜色的地产集收取租金。
 * requirements：无对应颜色的完整房产集时不能打出此牌（合法性校验在此实现）。
 */
public class RentEffect implements ActionEffect {

    /** 校验并计算应付租金；供效果栈入栈前使用（不扣款）。 */
    public static DueResult computeDue(ActionEffectContext ctx) {
        Player landlord = ctx.getActor();
        Player tenant = ctx.getTarget();
        String colorKey = ctx.getTargetColorKey();

        if (tenant == null) {
            return DueResult.error("必须指定收租目标玩家。");
        }
        if (colorKey == null || colorKey.isBlank()) {
            return DueResult.error("必须指定收租颜色。");
        }

        if (!PropertySetCalculator.hasCompleteSetForColor(landlord.getPropertyCardsView(), colorKey)) {
            return DueResult.error("当前无 " + colorKey + " 的完整房产集，无法打出收租牌。");
        }

        int due = RentCalculator.computeRentForColor(landlord, colorKey);
        if (due <= 0) {
            return DueResult.error("该颜色租金为 0，无需收租。");
        }
        return DueResult.ok(due);
    }

    public static final class DueResult {
        private final String error;
        private final int amountDue;

        private DueResult(String error, int amountDue) {
            this.error = error;
            this.amountDue = amountDue;
        }

        public static DueResult error(String message) {
            return new DueResult(message, 0);
        }

        public static DueResult ok(int amountDue) {
            return new DueResult(null, amountDue);
        }

        public boolean isOk() {
            return error == null;
        }

        public String getError() {
            return error;
        }

        public int getAmountDue() {
            return amountDue;
        }
    }

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        DueResult dueResult = computeDue(ctx);
        if (!dueResult.isOk()) {
            return ActionEffectResult.failed(dueResult.getError());
        }
        int due = dueResult.getAmountDue();
        Player landlord = ctx.getActor();
        Player tenant = ctx.getTarget();

        PaymentSettlement.Result result = PaymentSettlement.settle(tenant, landlord, due, ctx.getEngine());
        if (result.isSuccess()) {
            return ActionEffectResult.success(
                    "收租成功：" + tenant.getDisplayName() + " 向 " + landlord.getDisplayName()
                            + " 支付 " + result.getAmountPaid() + "M（应付 " + due + "M）。");
        } else {
            return ActionEffectResult.failed("收租失败：" + result.getMessage());
        }
    }
}
