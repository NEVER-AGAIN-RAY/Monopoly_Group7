package com.monopoly.controller;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.pattern.strategy.AiPlayStrategy;

/**
 * Runs AI turns after GameController.endTurn (HVM mode).
 * <p>
 * AiPlayStrategy plays via AiGameBridge
 * so AI uses the same validation and effect pipeline as humans.
 */
final class AiTurnService {

    private final GameController controller;
    private final TurnFlowService turnFlow;
    private boolean running;

    AiTurnService(GameController controller, TurnFlowService turnFlow) {
        this.controller = controller;
        this.turnFlow = turnFlow;
    }

    void executeAiTurn(AIPlayer ai) {
        if (running) {
            return;
        }
        running = true;
        try {
            controller.ensureNotPaused();
            controller.ensureSessionActive();
            controller.getGameContext().bindPlayers(controller.getSessionPlayersView());
            turnFlow.drawCards(ai, 2);
            continueAiTurnInternal(ai);
        } finally {
            running = false;
        }
    }

    void continueAiTurn(AIPlayer ai) {
        if (running) {
            return;
        }
        running = true;
        try {
            controller.ensureNotPaused();
            controller.ensureSessionActive();
            controller.getGameContext().bindPlayers(controller.getSessionPlayersView());
            continueAiTurnInternal(ai);
        } finally {
            running = false;
        }
    }

    private void continueAiTurnInternal(AIPlayer ai) {
        controller.ensureNotPaused();
        controller.ensureSessionActive();
        controller.getGameContext().bindPlayers(controller.getSessionPlayersView());

        AiPlayStrategy strategy = ai.getPlayStrategy();
        while (turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.PLAY
                && turnFlow.currentTurnActionCount < TurnFlowService.MAX_ACTIONS_PER_TURN
                && !ai.getHandCardsView().isEmpty()) {
            boolean progressed = strategy != null
                    && strategy.tryPlayOneCard(ai, controller.getGameContext(), controller);
            if (!progressed) {
                break;
            }
            if (turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
                return;
            }
        }
        if (turnFlow.currentTurnPhase == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            return;
        }
        turnFlow.forceDiscardOverflowToLimit(ai);
        controller.endTurn(ai);
    }
}
