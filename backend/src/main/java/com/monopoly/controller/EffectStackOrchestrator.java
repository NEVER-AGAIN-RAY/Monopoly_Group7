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

    private final ResponseTimeoutScheduler timeoutScheduler =
            new ResponseTimeoutScheduler(RESPONSE_WINDOW_SECONDS);

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
            throw new IllegalStateException("Invalid rent target.");
        }
        if (shouldAutoRespond(tenant)) {
            turnFlow.enterWaitingForResponse();
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
        turnFlow.enterWaitingForResponse();
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
            throw new IllegalStateException("Invalid action response target.");
        }
        if (shouldAutoRespond(target)) {
            turnFlow.enterWaitingForResponse();
            GameContext ctx = controller.getGameContext();
            pendingAction = new PendingAction(card, resolver, actionCountAfterPlay);
            ctx.setResponseState(
                    new StackResponseState(StackResponseState.Role.TENANT, target.getPlayerId(), 0L));
            ctx.pushEffect(EffectStackEntry.pendingAction(
                    turnFlow.currentTurnPlayerId(),
                    target.getPlayerId(),
                    card.getName(),
                    card.getEffectCode()));
            Player actor = controller.resolvePlayer(turnFlow.currentTurnPlayerId());
            String actorName = actor != null ? actor.getDisplayName() : turnFlow.currentTurnPlayerId();
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
        turnFlow.enterWaitingForResponse();
        scheduleResponseTimeoutIfNeeded(deadline);
        ctx.pushEffect(EffectStackEntry.pendingAction(
                turnFlow.currentTurnPlayerId(),
                target.getPlayerId(),
                card.getName(),
                card.getEffectCode()));
        Player actor = controller.resolvePlayer(turnFlow.currentTurnPlayerId());
        String actorName = actor != null ? actor.getDisplayName() : turnFlow.currentTurnPlayerId();
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
        timeoutScheduler.schedule(() -> {
            // Serialize with WebSocket worker threads and the AI decision thread:
            // all three share the per-session GameController monitor.
            synchronized (controller) {
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
                    recoverFromResolutionFailure(ex);
                }
            }
        });
    }

    /**
     * Last-resort recovery if resolving the effect stack throws: clearing the response
     * window and resuming the turn so the session cannot brick in WAITING_FOR_RESPONSE
     * (the timeout has already been cancelled, so no further timer would fire).
     */
    private void recoverFromResolutionFailure(RuntimeException ex) {
        System.err.println("[EFFECT_STACK] RESPONSE_TIMEOUT resolution failed; resuming turn: "
                + ex.getMessage());
        try {
            GameContext ctx = controller.getGameContext();
            ctx.clearEffectStack();
            ctx.clearRentChargeSequence();
            pendingAction = null;
            turnFlow.resumeToPlayOrEnd(turnFlow.actionCount());
            controller.pushSnapshot(controller.getCurrentSessionId(), "RESPONSE_TIMEOUT_ERROR",
                    "Response resolution failed; turn resumed.");
            controller.resumeAiTurnIfNeeded();
        } catch (RuntimeException recoveryFailure) {
            System.err.println("[EFFECT_STACK] recovery after timeout failure also failed: "
                    + recoveryFailure.getMessage());
        }
    }

    void scheduleResponseTimeoutIfNeeded(long deadlineEpochMs) {
        cancelPendingResponseTimeout();
        if (deadlineEpochMs <= 0L) {
            return;
        }
        scheduleResponseTimeout(deadlineEpochMs);
    }

    void cancelPendingResponseTimeout() {
        timeoutScheduler.cancel();
    }

    void shutdown() {
        timeoutScheduler.shutdown();
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
            throw new IllegalArgumentException("actingPlayerId is required when passing on a response.");
        }
        if (!controller.getGameContext().isAwaitingResponseFrom(actingPlayerId)) {
            throw new IllegalStateException("Not this player's turn to respond, or already timed out.");
        }
        GameContext ctx = controller.getGameContext();
        StackResponseState st = ctx.getResponseState();
        if (paymentCardIds != null && !paymentCardIds.isEmpty()) {
            if (st == null || st.getRole() != StackResponseState.Role.TENANT) {
                throw new IllegalStateException("Only the tenant may specify paymentCardIds when passing on rent waiver.");
            }
            List<EffectStackEntry> copy = new ArrayList<>(ctx.getEffectStackView());
            Set<String> cancelled = EffectStackResolver.computeCancelledEntryIds(copy);
            List<EffectStackEntry> active = EffectStackResolver.activeRentEntriesInOrder(copy, cancelled);
            if (active.isEmpty()) {
                throw new IllegalStateException("No rent due currently; do not specify paymentCardIds.");
            }
            EffectStackEntry first = active.get(0);
            if (!actingPlayerId.equals(first.getTenantPlayerId())) {
                throw new IllegalStateException("First rent charge is not directed at you; cannot specify paymentCardIds.");
            }
            Player tenant = controller.resolvePlayer(actingPlayerId);
            if (tenant == null) {
                throw new IllegalStateException("Tenant player not found.");
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
            throw new IllegalArgumentException("Valid actingPlayerId required during response phase.");
        }
        GameContext ctx = controller.getGameContext();
        if (!ctx.isAwaitingResponseFrom(actor.getPlayerId())) {
            throw new IllegalStateException("Not this player's turn to play a rent waiver card.");
        }
        ActionCard actionCard = resolveWaiverCard(actor, req, ctx);
        StackResponseState st = ctx.getResponseState();
        if (st == null) {
            throw new IllegalStateException("Response state lost.");
        }
        String targetId = resolveCounterTargetId(ctx, st);
        actor.placeActionToCenter(actionCard);
        ctx.pushEffect(EffectStackEntry.waiver(actor.getPlayerId(), targetId));
        cancelPendingResponseTimeout();
        if (st.getRole() == StackResponseState.Role.TENANT) {
            openLandlordCounterWindow(actor, actionCard, ctx);
        } else {
            resolveCounterImmediately(actor, actionCard);
        }
    }

    private ActionCard resolveWaiverCard(Player actor, PlayActionRequest req, GameContext ctx) {
        Card card = turnFlow.resolveCardInHand(actor, req.getCardId(), req.getHandIndex());
        if (!(card instanceof ActionCard actionCard)) {
            throw new IllegalArgumentException("Only action cards can be played in this phase.");
        }
        if (!"RENT_WAIVER".equalsIgnoreCase(actionCard.getEffectCode())) {
            throw new IllegalStateException("Can only play Just Say No (RENT_WAIVER) during response phase.");
        }
        ctx.bindPlayers(controller.getSessionPlayersView());
        ActionParamContext p = ActionParamContext.fromPlayRequest(req);
        if (!actionCard.canPlay(actor, p, ctx)) {
            throw new IllegalStateException("Cannot play RENT_WAIVER at this time.");
        }
        return actionCard;
    }

    private String resolveCounterTargetId(GameContext ctx, StackResponseState st) {
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
            throw new IllegalStateException("No counterable effect entry found.");
        }
        return targetId;
    }

    private void openLandlordCounterWindow(Player actor, ActionCard actionCard, GameContext ctx) {
        Player landlord = controller.resolvePlayer(turnFlow.currentTurnPlayerId());
        if (landlord == null) {
            throw new IllegalStateException("Current turn player lost.");
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
    }

    private void resolveCounterImmediately(Player actor, ActionCard actionCard) {
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

    // --- resolve effect stack ---

    void resolveEffectStackAndResume(String phaseHint) {
        resolveEffectStackAndResume(phaseHint, null, null);
    }

    void resolvePaymentChoiceForSimulation(String tenantId, List<String> explicitPaymentCardIds) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID must not be blank.");
        }
        GameContext ctx = controller.getGameContext();
        List<EffectStackEntry> copy = new ArrayList<>(ctx.getEffectStackView());
        Set<String> cancelled = EffectStackResolver.computeCancelledEntryIds(copy);
        List<EffectStackEntry> active = EffectStackResolver.activeRentEntriesInOrder(copy, cancelled);
        EffectStackEntry due = null;
        for (EffectStackEntry entry : active) {
            if (tenantId.equals(entry.getTenantPlayerId())) {
                due = entry;
                break;
            }
        }
        if (due == null) {
            throw new IllegalStateException("No rent due for this player in the effect stack.");
        }
        Player tenant = controller.resolvePlayer(tenantId);
        if (tenant == null) {
            throw new IllegalStateException("Paying player not found.");
        }
        PaymentSettlement.validateExplicitChoice(tenant, due.getAmountDue(), explicitPaymentCardIds);
        resolveEffectStackAndResume("COUNTERFACTUAL_PAYMENT", explicitPaymentCardIds, tenantId);
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
        controller.attachAuxiliaryDecisionMementoForTrace();
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
                            rentSeq.getAmountDuePerTenant(),
                            rentSeq.getSourceActionName(),
                            rentSeq.getSourceEffectCode()));
                    enterRentResponseWindow(nextTenant);
                    controller.pushSnapshot(controller.getCurrentSessionId(), phaseHint,
                            "Effect stack resolved: " + pay.getMessage() + " — next tenant.");
                    System.out.println("[EFFECT_STACK] " + phaseHint + " " + pay.getMessage()
                            + " (rent sequence continues)");
                    return;
                }
            }
            ctx.clearRentChargeSequence();
        }

        turnFlow.resumeToPlayOrEnd(turnFlow.actionCount());

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
        turnFlow.resumeToPlayOrEnd(pending.actionCountAfterPlay);
        ActionEffectResult result = cancelledAction
                ? ActionEffectResult.countered("Just Say No countered " + pending.card.getName() + ".")
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
                return "Someone played rent or action against you; you have "
                        + RESPONSE_WINDOW_SECONDS
                        + " seconds to play Just Say No, otherwise the action is accepted.";
            }
            return "Someone played rent or action against you; you may play Just Say No or accept.";
        }
        if (st.getDeadlineEpochMs() > 0L) {
            return "Opponent played Just Say No; you have "
                    + RESPONSE_WINDOW_SECONDS
                    + " seconds to counter with your own Just Say No, otherwise the counter is accepted.";
        }
        return "Opponent played Just Say No; you may counter with your own Just Say No or pass.";
    }

    private boolean shouldAutoRespond(Player player) {
        return player instanceof AIPlayer;
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
            controller.attachAuxiliaryDecisionMementoForTrace();
            return advisor.chooseResponse(ai, controller.getGameContext(), counterRole);
        }
        return AiHeuristics.chooseResponse(ai, controller.getGameContext(), counterRole);
    }

    private long responseDeadlineEpochMs() {
        return System.currentTimeMillis() + RESPONSE_WINDOW_SECONDS * 1000L;
    }

    private record PendingAction(
            ActionCard card,
            Supplier<ActionEffectResult> resolver,
            int actionCountAfterPlay) {
    }
}
