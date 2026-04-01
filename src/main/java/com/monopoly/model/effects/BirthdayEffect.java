package com.monopoly.model.effects;

import com.monopoly.model.PaymentSettlement;
import com.monopoly.model.Player;

import java.util.List;

/**
 * It's My Birthday：每位其他玩家向行动方支付 2M（从银行堆结算，无力足额则尽力支付失败条目标记）。
 */
public final class BirthdayEffect implements ActionEffect {

    private static final int GIFT_M = 2;

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        if (actor == null) {
            return ActionEffectResult.failed("缺少行动玩家。");
        }
        List<Player> all = ctx.getAllPlayers();
        if (all == null || all.size() < 2) {
            return ActionEffectResult.failed("人数不足，无法结算生日礼金。");
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
                note.append(p.getDisplayName()).append("未能支付2M;");
            }
        }
        return ActionEffectResult.success(
                "生日礼金共收入 " + totalPaid + "M。" + (note.length() > 0 ? " " + note : ""));
    }
}
