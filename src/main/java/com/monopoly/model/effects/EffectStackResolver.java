package com.monopoly.model.effects;

import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
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
        return resolveRentPayments(stackBottomToTop, players, engine, null, null);
    }

    /**
     * @param explicitPaymentCardIds 非空时：仅对<strong>第一条</strong>应付租金且承租人等于 {@code actingTenantIdForExplicit} 的条目使用指定牌；其余条目仍自动贪心。
     */
    public static PaymentSettlement.Result resolveRentPayments(
            List<EffectStackEntry> stackBottomToTop,
            List<Player> players,
            GameEngineSingleton engine,
            List<String> explicitPaymentCardIds,
            String actingTenantIdForExplicit) {

        if (stackBottomToTop == null || stackBottomToTop.isEmpty()) {
            return new PaymentSettlement.Result(
                    PaymentSettlement.Status.SUCCESS, 0, 0, "效果栈为空。");
        }

        Set<String> cancelled = computeCancelledEntryIds(stackBottomToTop);
        List<EffectStackEntry> activeRents = activeRentEntriesInOrder(stackBottomToTop, cancelled);

        PaymentSettlement.Result last = new PaymentSettlement.Result(
                PaymentSettlement.Status.SUCCESS, 0, 0, "无需支付。");
        boolean explicitConsumed = false;
        for (EffectStackEntry e : activeRents) {
            Player landlord = findPlayer(players, e.getActorPlayerId());
            Player tenant = findPlayer(players, e.getTenantPlayerId());
            if (landlord == null || tenant == null) {
                continue;
            }
            boolean useExplicit = !explicitConsumed
                    && explicitPaymentCardIds != null && !explicitPaymentCardIds.isEmpty()
                    && actingTenantIdForExplicit != null
                    && actingTenantIdForExplicit.equals(tenant.getPlayerId());
            if (useExplicit) {
                last = PaymentSettlement.settleWithExplicitCards(
                        tenant, landlord, e.getAmountDue(), explicitPaymentCardIds, engine);
                explicitConsumed = true;
            } else {
                last = PaymentSettlement.settle(tenant, landlord, e.getAmountDue(), engine);
            }
        }
        return last;
    }

    public static Set<String> computeCancelledEntryIds(List<EffectStackEntry> stackBottomToTop) {
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
        return cancelled;
    }

    public static List<EffectStackEntry> activeRentEntriesInOrder(
            List<EffectStackEntry> stackBottomToTop,
            Set<String> cancelledIds) {
        List<EffectStackEntry> out = new ArrayList<>();
        for (EffectStackEntry e : stackBottomToTop) {
            if (e.isRentLike() && !cancelledIds.contains(e.getId())) {
                out.add(e);
            }
        }
        return out;
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
