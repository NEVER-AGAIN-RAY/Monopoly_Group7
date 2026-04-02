package com.monopoly.model.effects;

import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从栈顶向下应用免租（后发先至），再对未被取消的收租条目执行支付。
 */
public final class EffectStackResolver {

    private EffectStackResolver() {
    }

    public static PaymentSettlement.Result resolveRentPayments(
            List<EffectStackEntry> stackBottomToTop,
            List<Player> players,
            GameEngineSingleton engine) {

        if (stackBottomToTop == null || stackBottomToTop.isEmpty()) {
            return new PaymentSettlement.Result(
                    PaymentSettlement.Status.SUCCESS, 0, 0, "效果栈为空。");
        }

        Set<String> cancelled = new HashSet<>();
        for (int i = stackBottomToTop.size() - 1; i >= 0; i--) {
            EffectStackEntry e = stackBottomToTop.get(i);
            if (cancelled.contains(e.getId())) {
                continue;
            }
            if (e.getKind() == EffectStackEntry.Kind.WAIVER) {
                String target = e.getWaiverTargetEntryId();
                if (target != null && !target.isBlank()) {
                    cancelled.add(target);
                }
            }
        }

        PaymentSettlement.Result last = new PaymentSettlement.Result(
                PaymentSettlement.Status.SUCCESS, 0, 0, "无需支付。");
        for (EffectStackEntry e : stackBottomToTop) {
            if (!e.isRentLike()) {
                continue;
            }
            if (cancelled.contains(e.getId())) {
                continue;
            }
            Player landlord = findPlayer(players, e.getActorPlayerId());
            Player tenant = findPlayer(players, e.getTenantPlayerId());
            if (landlord == null || tenant == null) {
                continue;
            }
            last = PaymentSettlement.settle(tenant, landlord, e.getAmountDue(), engine);
        }
        return last;
    }

    private static Player findPlayer(List<Player> players, String playerId) {
        if (players == null || playerId == null) {
            return null;
        }
        for (Player p : players) {
            if (playerId.equals(p.getPlayerId())) {
                return p;
            }
        }
        return null;
    }
}
