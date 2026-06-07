package com.monopoly.model.effects;

import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;

import java.util.List;

/**
 * It's My Birthday: each other player pays 2M from bank (partial pay marks failed entries).
 */
public final class BirthdayEffect implements ActionEffect {

    private static final int GIFT_M = 2;

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        if (actor == null) {
            return ActionEffectResult.failed("Missing acting player.");
        }
        List<Player> all = ctx.getAllPlayers();
        if (all == null || all.size() < 2) {
            return ActionEffectResult.failed("Not enough players to settle birthday gifts.");
        }
        int totalPaid = 0;
        StringBuilder note = new StringBuilder();
        for (Player p : all) {
            if (p == null || p == actor) {
                continue;
            }
            PaymentSettlement.Result r = PaymentSettlement.settle(p, actor, GIFT_M, ctx.getEngine());
            if (r.isSuccess()) {
                totalPaid += r.getAmountPaid();
            } else {
                if (note.length() > 0) {
                    note.append(" ");
                }
                note.append(p.getDisplayName()).append(" could not pay 2M;");
            }
        }
        return ActionEffectResult.success(
                "Birthday gifts collected " + totalPaid + "M in total." + (note.length() > 0 ? " " + note : ""));
    }
}
