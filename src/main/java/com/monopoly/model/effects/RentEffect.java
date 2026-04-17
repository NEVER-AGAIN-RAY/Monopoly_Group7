package com.monopoly.model.effects;

import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.RentCalculator;

/**
 * 收租效果：须施效者该色已有<strong>完整套</strong>；金额 = 牌面租金表 + 房/旅馆加值。
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

        String ck = colorKey.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PropertySetCalculator.hasCompleteSetForColor(landlord.getPropertyCardsView(), ck)) {
            return DueResult.error("你在财产区没有 " + ck + " 的完整房产套，无法打出收租牌。");
        }

        int due = RentCalculator.computeRentForColor(landlord, colorKey);
        if (due <= 0) {
            return DueResult.error("该颜色租金为 0，无需收租。");
        }
        return DueResult.ok(due);
    }

    /**
     * {@link com.monopoly.model.card.ActionCard} 的 RENT_DUAL：仅校验房东所选颜色完整套并计算金额（承租人由序列决定）。
     */
    public static DueResult computeDueLandlordColorOnly(ActionEffectContext ctx) {
        Player landlord = ctx.getActor();
        String colorKey = ctx.getTargetColorKey();
        if (landlord == null) {
            return DueResult.error("房东无效。");
        }
        if (colorKey == null || colorKey.isBlank()) {
            return DueResult.error("必须指定收租颜色。");
        }
        String ck = colorKey.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PropertySetCalculator.hasCompleteSetForColor(landlord.getPropertyCardsView(), ck)) {
            return DueResult.error("你在财产区没有 " + ck + " 的完整房产套，无法打出该双色收租牌。");
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
