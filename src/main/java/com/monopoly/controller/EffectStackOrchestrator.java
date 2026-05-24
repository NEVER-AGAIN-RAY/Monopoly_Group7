package com.monopoly.controller;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.EffectStackResolver;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.core.RentChargeSequence;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.pattern.strategy.AiHeuristics;
import com.monopoly.pattern.strategy.AiChoiceAdvisor;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates rent/Just-Say-No response windows and 20s PVP timeouts (extracted from GameController).
 * <p>
 * Uses a single-thread scheduler; cooperates with TurnFlowService for phase changes.
 * <p>
 * Timeout tasks compare deadlineEpochMs so stale timers cannot race with live responses.
 */
final class EffectStackOrchestrator {

    static final int RESPONSE_WINDOW_SECONDS = 20;

    private final GameController controller;
    private final TurnFlowService turnFlow;
    private final GameEngineSingleton engine;
    private PendingAction pendingAction;

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
        this.engine = controller.getEngine();
    }

    // --- response window ---

    void enterRentResponseWindow(Player tenant) {
        enterRentResponseWindow(tenant, null, null);
    }

    void enterRentResponseWindow(Player tenant, Player playedBy, ActionCard playedCard) {
        if (tenant == null) {
            throw new IllegalStateException("收租目标无效。");
        }
        if (shouldAutoRespond(tenant)) {
            turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE;
            GameContext ctx = controller.getGameContext();
            ctx.setResponseState(
                    new StackResponseState(StackResponseState.Role.TENANT, tenant.getPlayerId(), 0L));
            if (playedCard != null) {
                controller.pushSnapshot(controller.getCurrentSessionId(),
                        "RENT_AWAITING_RESPONSE",
                        "Rent awaiting automatic response from " + tenant.getDisplayName() + ".",
                        playedBy,
                        playedCard,
                        "ACTION");
            }
            autoRespondFromCurrentWindow(
                    (AIPlayer) tenant,
                    "RENT_AI_RESPONSE_PASS",
                    tenant.getDisplayName() + " auto-accepted the charge.");
            return;
        }
        GameContext ctx = controller.getGameContext();
        long deadline = responseDeadlineEpochMs();
        ctx.setResponseState(
                new StackResponseState(StackResponseState.Role.TENANT, tenant.getPlayerId(), deadline));
        turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE;
        scheduleResponseTimeoutIfNeeded(deadline);
        EffectStackEntry top = ctx.peekTopEffect();
        int due = top != null ? top.getAmountDue() : 0;
        String rentSummary = "Rent " + due + "M — awaiting response from "
                + tenant.getDisplayName() + ".";
        controller.pushSnapshot(controller.getCurrentSessionId(),
                "RENT_AWAITING_RESPONSE", rentSummary, playedBy, playedCard, "ACTION");
    }

    void enterActionResponseWindow(
            Player target,
            ActionCard card,
            Supplier<ActionEffectResult> resolver,
            int actionCountAfterPlay) {
        if (target == null || card == null || resolver == null) {
            throw new IllegalStateException("行动响应目标无效。");
        }
        if (shouldAutoRespond(target)) {
            turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE;
            GameContext ctx = controller.getGameContext();
            pendingAction = new PendingAction(card, resolver, actionCountAfterPlay);
            ctx.setResponseState(
                    new StackResponseState(StackResponseState.Role.TENANT, target.getPlayerId(), 0L));
            ctx.pushEffect(EffectStackEntry.pendingAction(
                    turnFlow.currentTurnPlayerId,
                    target.getPlayerId(),
                    card.getName(),
                    card.getEffectCode()));
            Player actor = controller.resolvePlayer(turnFlow.currentTurnPlayerId);
            String actorName = actor != null ? actor.getDisplayName() : turnFlow.currentTurnPlayerId;
            controller.pushSnapshot(controller.getCurrentSessionId(),
                    "ACTION_AWAITING_RESPONSE",
                    actorName + " played ACTION (" + card.getName()
                            + ") — awaiting automatic response from " + target.getDisplayName() + ".",
                    actor,
                    card,
                    "ACTION");
            autoRespondFromCurrentWindow(
                    (AIPlayer) target,
                    "ACTION_AI_RESPONSE_PASS",
                    target.getDisplayName() + " auto-accepted " + card.getName() + ".");
            return;
        }
        GameContext ctx = controller.getGameContext();
        long deadline = responseDeadlineEpochMs();
        pendingAction = new PendingAction(card, resolver, actionCountAfterPlay);
        ctx.setResponseState(
                new StackResponseState(StackResponseState.Role.TENANT, target.getPlayerId(), deadline));
        turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE;
        scheduleResponseTimeoutIfNeeded(deadline);
        ctx.pushEffect(EffectStackEntry.pendingAction(
                turnFlow.currentTurnPlayerId,
                target.getPlayerId(),
                card.getName(),
                card.getEffectCode()));
        Player actor = controller.resolvePlayer(turnFlow.currentTurnPlayerId);
        String actorName = actor != null ? actor.getDisplayName() : turnFlow.currentTurnPlayerId;
        controller.pushSnapshot(controller.getCurrentSessionId(),
                "ACTION_AWAITING_RESPONSE",
                actorName + " played ACTION (" + card.getName()
                        + ") — awaiting Just Say No response from " + target.getDisplayName() + ".",
                actor,
                card,
                "ACTION");
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
                if (pendingAction != null) {
                    resolvePendingActionAndResume("RESPONSE_TIMEOUT");
                } else {
                    resolveEffectStackAndResume("RESPONSE_TIMEOUT", null, null);
                }
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
        }, RESPONSE_WINDOW_SECONDS, TimeUnit.SECONDS);
    }

    void scheduleResponseTimeoutIfNeeded(long deadlineEpochMs) {
        cancelPendingResponseTimeout();
        if (deadlineEpochMs <= 0L) {
            return;
        }
        scheduleResponseTimeout(deadlineEpochMs);
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
        if (pendingAction != null) {
            resolvePendingActionAndResume("RESPONSE_PASS");
        } else {
            resolveEffectStackAndResume("RESPONSE_PASS", paymentCardIds, tenantExplicit);
        }
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
            targetId = pendingAction != null
                    ? ctx.findBottomActionEntryId()
                    : ctx.findBottomRentEntryId();
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
            if (shouldAutoRespond(landlord)) {
                ctx.setResponseState(new StackResponseState(
                        StackResponseState.Role.LANDLORD_COUNTER, landlord.getPlayerId(), 0L));
                controller.pushSnapshot(controller.getCurrentSessionId(), "JSN_AWAITING_COUNTER",
                        actor.getDisplayName() + " played Just Say No; landlord may counter.",
                        actor,
                        actionCard,
                        "ACTION");
                autoRespondFromCurrentWindow(
                        (AIPlayer) landlord,
                        "JSN_AI_COUNTER_PASS",
                        landlord.getDisplayName() + " auto-passed Just Say No counter.");
                return;
            }
            long deadline = responseDeadlineEpochMs();
            ctx.setResponseState(new StackResponseState(
                    StackResponseState.Role.LANDLORD_COUNTER, landlord.getPlayerId(), deadline));
            scheduleResponseTimeoutIfNeeded(deadline);
            controller.pushSnapshot(controller.getCurrentSessionId(), "JSN_AWAITING_COUNTER",
                    actor.getDisplayName() + " played Just Say No; landlord may counter.",
                    actor,
                    actionCard,
                    "ACTION");
        } else {
            controller.pushSnapshot(controller.getCurrentSessionId(), "JSN_COUNTER_PLAYED",
                    actor.getDisplayName() + " played Just Say No to counter.",
                    actor,
                    actionCard,
                    "ACTION");
            if (pendingAction != null) {
                resolvePendingActionAndResume("JSN_COUNTER_RESOLVED");
            } else {
                resolveEffectStackAndResume("JSN_COUNTER_RESOLVED", null, null);
            }
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
        pendingAction = null;
        ctx.clearEffectStack();

        PaymentSettlement.Result pay = EffectStackResolver.resolveRentPayments(
                copy,
                controller.getSessionPlayersView(),
                engine,
                explicitPaymentCardIds,
                actingTenantIdForExplicit,
                ctx);

        if (rentSeq != null) {
            boolean moreTenants = rentSeq.advanceToNextTenant();
            if (moreTenants) {
                if (controller.isSessionForceEnded()) {
                    return;
                }
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
        controller.resumeAiTurnIfNeeded();
    }

    private void resolvePendingActionAndResume(String phaseHint) {
        PendingAction pending = pendingAction;
        if (pending == null) {
            resolveEffectStackAndResume(phaseHint, null, null);
            return;
        }
        GameContext ctx = controller.getGameContext();
        List<EffectStackEntry> copy = new ArrayList<>(ctx.getEffectStackView());
        Set<String> cancelled = EffectStackResolver.computeCancelledEntryIds(copy);
        boolean cancelledAction = copy.stream()
                .filter(EffectStackEntry::isActionLike)
                .anyMatch(e -> cancelled.contains(e.getId()));
        pendingAction = null;
        ctx.clearEffectStack();
        turnFlow.currentTurnPhase = pending.actionCountAfterPlay >= TurnFlowService.MAX_ACTIONS_PER_TURN
                ? TurnFlowService.TurnPhase.END_TURN
                : TurnFlowService.TurnPhase.PLAY;
        ActionEffectResult result = cancelledAction
                ? ActionEffectResult.countered("Just Say No 抵消了 " + pending.card.getName() + "。")
                : pending.resolver.get();
        String phase = result.isSuccess() ? "ACTION_SUCCESS"
                : (result.getStatus() == ActionEffectResult.Status.COUNTERED
                        ? "ACTION_COUNTERED" : "ACTION_FAILED");
        controller.pushSnapshot(controller.getCurrentSessionId(), phaseHint,
                "Effect stack resolved: " + result.getMessage());
        controller.pushSnapshot(controller.getCurrentSessionId(), phase,
                "Action effect resolved: " + pending.card.getName() + " — " + result.getMessage());
        System.out.println("[EFFECT_STACK] " + phaseHint + " " + result.getMessage());
        controller.resumeAiTurnIfNeeded();
    }

    // --- UI hint text ---

    static String buildPendingResponseHint(StackResponseState st) {
        if (st == null) {
            return null;
        }
        if (st.getRole() == StackResponseState.Role.TENANT) {
            if (st.getDeadlineEpochMs() > 0L) {
                return "有人对你打出收租或行动，你有 "
                        + RESPONSE_WINDOW_SECONDS
                        + " 秒打出 Just Say No，否则默认接受。";
            }
            return "有人对你打出收租或行动，你可以打出 Just Say No，也可以接受。";
        }
        if (st.getDeadlineEpochMs() > 0L) {
            return "对方打出免租，你有 "
                    + RESPONSE_WINDOW_SECONDS
                    + " 秒打出 Just Say No 反制，否则默认放弃反制。";
        }
        return "对方打出免租，你可以打出 Just Say No 反制，也可以放弃反制。";
    }

    private boolean shouldAutoRespond(Player player) {
        return !controller.isPvpMode() && player instanceof AIPlayer;
    }

    private void autoRespondFromCurrentWindow(
            AIPlayer ai,
            String passPhase,
            String passSummary) {
        StackResponseState st = controller.getGameContext().getResponseState();
        boolean counterRole = st != null && st.getRole() == StackResponseState.Role.LANDLORD_COUNTER;
        AiHeuristics.AiResponseDecision decision = chooseAiResponse(ai, counterRole);
        if (decision.playWaiver() && decision.request() != null) {
            handleWaiverPlay(decision.request());
            return;
        }
        controller.pushSnapshot(controller.getCurrentSessionId(),
                passPhase,
                passSummary);
        performResponsePass(ai.getPlayerId());
    }

    private AiHeuristics.AiResponseDecision chooseAiResponse(AIPlayer ai, boolean counterRole) {
        if (ai.getPlayStrategy() instanceof AiChoiceAdvisor advisor) {
            return advisor.chooseResponse(ai, controller.getGameContext(), counterRole);
        }
        return AiHeuristics.chooseResponse(ai, controller.getGameContext(), counterRole);
    }

    private long responseDeadlineEpochMs() {
        return controller.isPvpMode()
                ? System.currentTimeMillis() + RESPONSE_WINDOW_SECONDS * 1000L
                : 0L;
    }

    private record PendingAction(
            ActionCard card,
            Supplier<ActionEffectResult> resolver,
            int actionCountAfterPlay) {
    }
}
