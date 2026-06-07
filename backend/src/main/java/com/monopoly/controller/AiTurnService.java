package com.monopoly.controller;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.pattern.strategy.AiPlayStrategy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs AI turns after GameController.endTurn (HVM mode).
 * <p>
 * AiPlayStrategy plays via AiGameBridge
 * so AI uses the same validation and effect pipeline as humans.
 */
final class AiTurnService {

    private final GameController controller;
    private final TurnFlowService turnFlow;
    private final ScheduledExecutorService decisionScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-turn-decision");
                t.setDaemon(true);
                return t;
            });

    AiTurnService(GameController controller, TurnFlowService turnFlow) {
        this.controller = controller;
        this.turnFlow = turnFlow;
    }

    void executeAiTurn(AIPlayer ai) {
        schedule(ai, true);
    }

    void continueAiTurn(AIPlayer ai) {
        schedule(ai, false);
    }

    private void schedule(AIPlayer ai, boolean needsDraw) {
        if (ai == null || controller.isSessionEnded()) {
            return;
        }
        String sessionId = controller.getCurrentSessionId();
        decisionScheduler.schedule(
                () -> runScheduled(ai, needsDraw, sessionId),
                decisionDelayMs(),
                TimeUnit.MILLISECONDS);
    }

    private void runScheduled(AIPlayer ai, boolean needsDraw, String sessionId) {
        if (sessionId == null || !sessionId.equals(controller.getCurrentSessionId())
                || controller.isSessionEnded()) {
            return;
        }
        runOne(ai, needsDraw);
    }

    private void runOne(AIPlayer ai, boolean needsDraw) {
        try {
            controller.ensureNotPaused();
            controller.ensureSessionActive();
            controller.refreshAiDecisionContext();
            if (needsDraw) {
                turnFlow.drawCards(ai, 2);
                schedule(ai, false);
                return;
            }
            continueAiTurnOneDecision(ai);
        } catch (IllegalStateException ex) {
            if (!controller.isSessionEnded()) {
                handleAiDecisionFailure(ai, ex);
            }
        } catch (RuntimeException ex) {
            if (!controller.isSessionEnded()) {
                handleAiDecisionFailure(ai, ex);
            }
        }
    }

    private void continueAiTurnOneDecision(AIPlayer ai) {
        controller.ensureNotPaused();
        controller.ensureSessionActive();
        if (controller.getCurrentPlayer() != ai) {
            return;
        }
        controller.refreshAiDecisionContext();

        AiPlayStrategy strategy = ai.getPlayStrategy();
        if (turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.PLAY
                && turnFlow.currentTurnActionCount < TurnFlowService.MAX_ACTIONS_PER_TURN
                && !controller.isSessionForceEnded()
                && !ai.getHandCardsView().isEmpty()) {
            boolean progressed = strategy != null
                    && strategy.tryPlayOneCard(ai, controller.getGameContext(), controller);
            if (controller.isSessionForceEnded()) {
                return;
            }
            if (!progressed) {
                finishAiTurn(ai);
                return;
            }
            if (turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
                return;
            }
            if (turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.PLAY
                    && turnFlow.currentTurnActionCount < TurnFlowService.MAX_ACTIONS_PER_TURN
                    && !ai.getHandCardsView().isEmpty()) {
                schedule(ai, false);
                return;
            }
        }
        finishAiTurn(ai);
    }

    private void finishAiTurn(AIPlayer ai) {
        if (turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            return;
        }
        if (controller.isSessionForceEnded()) {
            return;
        }
        turnFlow.forceDiscardOverflowToLimit(ai);
        if (controller.isSessionForceEnded()) {
            return;
        }
        controller.endTurn(ai);
    }

    private void handleAiDecisionFailure(AIPlayer ai, RuntimeException ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
        System.err.println("[AI_TURN] " + ai.getPlayerId() + " decision failed: " + message);
        controller.recordError("AI_DECISION_FAILED", ai.getDisplayName() + " decision failed; this action was skipped: " + message);
        if (controller.isSessionEnded()
                || controller.getCurrentPlayer() != ai
                || turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            controller.pushSnapshot(controller.getCurrentSessionId(), "AI_DECISION_FAILED");
            return;
        }
        if (turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.DRAW) {
            turnFlow.skipDrawAfterAiFailure(ai);
        }
        finishAiTurn(ai);
    }

    private static long decisionDelayMs() {
        return Math.max(0L, Long.getLong("monopoly.ai.decisionDelayMs", 75L));
    }

    void shutdown() {
        decisionScheduler.shutdownNow();
    }
}
