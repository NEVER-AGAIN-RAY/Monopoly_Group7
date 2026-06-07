package com.monopoly.model.effects;

import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.RentCalculator;

/**
 * Rent: landlord needs 1+ card in color; due = tier table + house/hotel bonus.
 */
public class RentEffect implements ActionEffect {

    /** Validate the rent request and compute the amount due without moving payment cards. */
    public static DueResult computeDue(ActionEffectContext ctx) {
        Player landlord = ctx.getActor();
        Player tenant = ctx.getTarget();
        String colorKey = ctx.getTargetColorKey();

        if (landlord == null) {
            return DueResult.error("Invalid landlord.");
        }
        if (tenant == null) {
            return DueResult.error("A target player must be specified to charge rent.");
        }
        if (colorKey == null || colorKey.isBlank()) {
            return DueResult.error("A rent color must be specified.");
        }

        String ck = colorKey.trim().toUpperCase(java.util.Locale.ROOT);
        int due = RentCalculator.computeRentForColor(landlord, ck);
        if (due <= 0) {
            return DueResult.error("You have no " + ck + " property in your property zone; cannot charge rent.");
        }
        return DueResult.ok(due);
    }

    /**
     * RENT_DUAL: validate landlord color and amount; tenants come from RentChargeSequence.
     */
    public static DueResult computeDueLandlordColorOnly(ActionEffectContext ctx) {
        Player landlord = ctx.getActor();
        String colorKey = ctx.getTargetColorKey();
        if (landlord == null) {
            return DueResult.error("Invalid landlord.");
        }
        if (colorKey == null || colorKey.isBlank()) {
            return DueResult.error("A rent color must be specified.");
        }
        String ck = colorKey.trim().toUpperCase(java.util.Locale.ROOT);
        int due = RentCalculator.computeRentForColor(landlord, ck);
        if (due <= 0) {
            return DueResult.error("You have no " + ck + " property in your property zone; cannot play this dual-color rent card.");
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
                    "Rent collected: " + tenant.getDisplayName() + " paid " + landlord.getDisplayName()
                            + " " + result.getAmountPaid() + "M (due " + due + "M).");
        } else {
            return ActionEffectResult.failed("Rent collection failed: " + result.getMessage());
        }
    }
}
