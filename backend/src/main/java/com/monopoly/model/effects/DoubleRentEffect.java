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
            return RentEffect.DueResult.error("Invalid landlord.");
        }
        if (tenant == null) {
            return RentEffect.DueResult.error("Double rent requires a target player.");
        }
        if (colorKey == null || colorKey.isBlank()) {
            return RentEffect.DueResult.error("Double rent requires a color.");
        }

        String ck = colorKey.trim().toUpperCase(java.util.Locale.ROOT);
        int base = RentCalculator.computeRentForColor(landlord, ck);
        int due = base * 2;
        if (due <= 0) {
            return RentEffect.DueResult.error("You have no " + ck + " property in your property zone; cannot charge double rent.");
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
                    "Double rent collected: " + tenant.getDisplayName() + " paid " + result.getAmountPaid()
                            + "M (base " + base + "M x 2).");
        } else {
            return ActionEffectResult.failed("Double rent collection failed: " + result.getMessage());
        }
    }
}
