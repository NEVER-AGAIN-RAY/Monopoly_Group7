package com.monopoly.controller;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.EffectStackResolver;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.core.RentChargeSequence;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates rent/Just-Say-No response windows and 15s timeouts (extracted from GameController).
 * <p>
 * Uses a single-thread scheduler; cooperates with TurnFlowService for phase changes.
 * <p>
 * Timeout tasks compare deadlineEpochMs so stale timers cannot race with live responses.
 */
final class EffectStackOrchestrator {

    static final int RESPONSE_WINDOW_SECONDS = 15;

    private final GameController controller;
    private final TurnFlowService turnFlow;
    private final GameEngineSingleton engine = GameEngineSingleton.getInstance();

    private final ScheduledExecutorService responseScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "effect-response-timeout");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pendingResponseFuture;

    EffectStackOrchestrator(GameController controller, TurnFlowService turnFlow) {
        this.controller = controller;
        this.turnFlow = turnFlow;
    }

    // --- response window ---

    void enterRentResponseWindow(Player tenant) {
        if (tenant == null) {
            throw new IllegalStateException("收租目标无效。");
        }
        GameContext ctx = controller.getGameContext();
        long deadline = System.currentTimeMillis() + RESPONSE_WINDOW_SECONDS * 1000L;
        ctx.setResponseState(
                new StackResponseState(StackResponseState.Role.TENANT, tenant.getPlayerId(), deadline));
        turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE;
        scheduleResponseTimeout(deadline);
        EffectStackEntry top = ctx.peekTopEffect();
        int due = top != null ? top.getAmountDue() : 0;
        String rentSummary = "Rent " + due + "M — awaiting response from "
                + tenant.getDisplayName() + ".";
        controller.pushSnapshot(controller.getCurrentSessionId(),
                "RENT_AWAITING_RESPONSE", rentSummary);
    }

    // --- timeout scheduler ---

    void scheduleResponseTimeout(long deadlineEpochMs) {
        cancelPendingResponseTimeout();
        pendingResponseFuture = responseScheduler.schedule(() -> {
            StackResponseState st = controller.getGameContext().getResponseState();
            if (st == null || st.getDeadlineEpochMs() != deadlineEpochMs) {
                return;
            }
            try {
                cancelPendingResponseTimeout();
                resolveEffectStackAndResume("RESPONSE_TIMEOUT", null, null);
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
        }, RESPONSE_WINDOW_SECONDS, TimeUnit.SECONDS);
    }

    void cancelPendingResponseTimeout() {
        if (pendingResponseFuture != null) {
            pendingResponseFuture.cancel(false);
            pendingResponseFuture = null;
        }
    }

    // --- pass / explicit payment ---

    void performResponsePass(String actingPlayerId) {
        performResponsePass(actingPlayerId, null);
    }

    /**
     * When non-empty, tenant picks bank/property card ids for the first rent due (no change returned); TENANT role only.
     */
    void performResponsePass(String actingPlayerId, List<String> paymentCardIds) {
        if (actingPlayerId == null || actingPlayerId.isBlank()) {
            throw new IllegalArgumentException("放弃响应时必须提供 actingPlayerId。");
        }
        if (!controller.getGameContext().isAwaitingResponseFrom(actingPlayerId)) {
            throw new IllegalStateException("当前未轮到该玩家响应或已超时。");
        }
        GameContext ctx = controller.getGameContext();
        StackResponseState st = ctx.getResponseState();
        if (paymentCardIds != null && !paymentCardIds.isEmpty()) {
            if (st == null || st.getRole() != StackResponseState.Role.TENANT) {
                throw new IllegalStateException("仅承租人在放弃免租时可指定 paymentCardIds。");
            }
            List<EffectStackEntry> copy = new ArrayList<>(ctx.getEffectStackView());
            Set<String> cancelled = EffectStackResolver.computeCancelledEntryIds(copy);
            List<EffectStackEntry> active = EffectStackResolver.activeRentEntriesInOrder(copy, cancelled);
            if (active.isEmpty()) {
                throw new IllegalStateException("当前无应付租金，请勿指定 paymentCardIds。");
            }
            EffectStackEntry first = active.get(0);
            if (!actingPlayerId.equals(first.getTenantPlayerId())) {
                throw new IllegalStateException("首条应付租金不指向你，不能指定 paymentCardIds。");
            }
            Player tenant = controller.resolvePlayer(actingPlayerId);
            if (tenant == null) {
                throw new IllegalStateException("承租人玩家不存在。");
            }
            PaymentSettlement.validateExplicitChoice(tenant, first.getAmountDue(), paymentCardIds);
        }
        cancelPendingResponseTimeout();
        String tenantExplicit = (paymentCardIds != null && !paymentCardIds.isEmpty())
                ? actingPlayerId
                : null;
        resolveEffectStackAndResume("RESPONSE_PASS", paymentCardIds, tenantExplicit);
    }

    // --- Just Say No (rent waiver) ---

    void handleWaiverPlay(PlayActionRequest req) {
        Player actor = controller.resolvePlayer(req.getActingPlayerId());
        if (actor == null) {
            throw new IllegalArgumentException("等待响应阶段必须提供有效的 actingPlayerId。");
        }
        GameContext ctx = controller.getGameContext();
        if (!ctx.isAwaitingResponseFrom(actor.getPlayerId())) {
            throw new IllegalStateException("当前未轮到该玩家打出免租牌。");
        }
        Card card = turnFlow.resolveCardInHand(actor, req.getCardId(), req.getHandIndex());
        if (!(card instanceof ActionCard actionCard)) {
            throw new IllegalArgumentException("只能打出行动卡。");
        }
        if (!"RENT_WAIVER".equalsIgnoreCase(actionCard.getEffectCode())) {
            throw new IllegalStateException("当前只能打出免租牌（Just Say No）。");
        }
        ctx.bindPlayers(controller.getSessionPlayersView());
        ActionParamContext p = ActionParamContext.fromPlayRequest(req);
        if (!actionCard.canPlay(actor, p, ctx)) {
            throw new IllegalStateException("当前不能打出免租牌。");
        }
        StackResponseState st = ctx.getResponseState();
        if (st == null) {
            throw new IllegalStateException("响应状态丢失。");
        }
        String targetId;
        if (st.getRole() == StackResponseState.Role.TENANT) {
            targetId = ctx.findBottomRentEntryId();
        } else {
            EffectStackEntry top = ctx.peekTopEffect();
            targetId = top != null ? top.getId() : null;
        }
        if (targetId == null) {
            throw new IllegalStateException("找不到可抵消的效果条目。");
        }

        actor.placeActionToCenter(actionCard);
        ctx.pushEffect(EffectStackEntry.waiver(actor.getPlayerId(), targetId));
        cancelPendingResponseTimeout();

        if (st.getRole() == StackResponseState.Role.TENANT) {
            Player landlord = controller.resolvePlayer(turnFlow.currentTurnPlayerId);
            if (landlord == null) {
                throw new IllegalStateException("当前回合玩家丢失。");
            }
            long deadline = System.currentTimeMillis() + RESPONSE_WINDOW_SECONDS * 1000L;
            ctx.setResponseState(new StackResponseState(
                    StackResponseState.Role.LANDLORD_COUNTER, landlord.getPlayerId(), deadline));
            scheduleResponseTimeout(deadline);
            controller.pushSnapshot(controller.getCurrentSessionId(), "JSN_AWAITING_COUNTER",
                    actor.getDisplayName() + " played Just Say No; landlord may counter.");
        } else {
            resolveEffectStackAndResume("JSN_COUNTER_RESOLVED", null, null);
        }
    }

    // --- resolve effect stack ---

    void resolveEffectStackAndResume(String phaseHint) {
        resolveEffectStackAndResume(phaseHint, null, null);
    }

    void resolveEffectStackAndResume(
            String phaseHint,
            List<String> explicitPaymentCardIds,
            String actingTenantIdForExplicit) {
        if (controller.isSessionForceEnded()) {
            return;
        }
        GameContext ctx = controller.getGameContext();
        List<EffectStackEntry> copy = new ArrayList<>(ctx.getEffectStackView());
        RentChargeSequence rentSeq = ctx.getRentChargeSequence();
        ctx.clearEffectStack();

        PaymentSettlement.Result pay = EffectStackResolver.resolveRentPayments(
                copy,
                controller.getSessionPlayersView(),
                engine,
                explicitPaymentCardIds,
                actingTenantIdForExplicit);

        if (rentSeq != null) {
            boolean moreTenants = rentSeq.advanceToNextTenant();
            if (moreTenants) {
                String nextId = rentSeq.getCurrentTenantId();
                Player nextTenant = controller.resolvePlayer(nextId);
                if (nextTenant != null) {
                    ctx.pushEffect(EffectStackEntry.pendingRent(
                            rentSeq.getLandlordId(),
                            nextId,
                            rentSeq.getColorKey(),
                            rentSeq.getAmountDuePerTenant()));
                    enterRentResponseWindow(nextTenant);
                    controller.pushSnapshot(controller.getCurrentSessionId(), phaseHint,
                            "Effect stack resolved: " + pay.getMessage() + " — 下一名承租人。");
                    System.out.println("[EFFECT_STACK] " + phaseHint + " " + pay.getMessage()
                            + " (rent sequence continues)");
                    return;
                }
            }
            ctx.clearRentChargeSequence();
        }

        turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.PLAY;
        if (turnFlow.currentTurnActionCount >= TurnFlowService.MAX_ACTIONS_PER_TURN) {
            turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.END_TURN;
        }

        controller.pushSnapshot(controller.getCurrentSessionId(), phaseHint,
                "Effect stack resolved: " + pay.getMessage());
        System.out.println("[EFFECT_STACK] " + phaseHint + " " + pay.getMessage());
    }

    // --- UI hint text ---

    static String buildPendingResponseHint(StackResponseState st) {
        if (st == null) {
            return null;
        }
        if (st.getRole() == StackResponseState.Role.TENANT) {
            return "有人向你收租，你有 "
                    + RESPONSE_WINDOW_SECONDS
                    + " 秒打出 Just Say No，否则默认接受。";
        }
        return "对方打出免租，你有 "
                + RESPONSE_WINDOW_SECONDS
                + " 秒打出 Just Say No 反制，否则默认放弃反制。";
    }
}
