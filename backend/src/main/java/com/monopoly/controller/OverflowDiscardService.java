package com.monopoly.controller;

import com.monopoly.model.card.Card;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.strategy.AiChoiceAdvisor;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.List;

final class OverflowDiscardService {

    private final GameController controller;
    private final GameEngineSingleton engine;
    private final TurnState turnState;

    OverflowDiscardService(GameController controller, GameEngineSingleton engine, TurnState turnState) {
        this.controller = controller;
        this.engine = engine;
        this.turnState = turnState;
    }

    boolean handleForcedOverflowDiscard(Player player, Card card) {
        if (player.getHandCardCount() > TurnFlowService.MAX_HAND_SIZE
                && (turnState.mustDiscardOverflow() || turnState.phase() == TurnFlowService.TurnPhase.END_TURN)) {
            if (turnState.phase() != TurnFlowService.TurnPhase.PLAY && turnState.phase() != TurnFlowService.TurnPhase.END_TURN) {
                throw new IllegalStateException("Cannot discard in current phase.");
            }
            if (!player.discardFromHand(card)) {
                throw new IllegalStateException("Failed to discard card from hand.");
            }
            engine.discard(card);
            if (player.getHandCardCount() <= TurnFlowService.MAX_HAND_SIZE) {
                turnState.setMustDiscardOverflow(false);
            }
            controller.pushSnapshot(
                    controller.getCurrentSessionId(),
                    "FORCE_DISCARD",
                    player.getDisplayName() + " force-discarded overflow card (" + card.getName() + ").",
                    player,
                    card,
                    "DISCARD");
            return true;
        }
        return false;
    }

    void forceDiscardOverflowToLimit(Player player) {
        if (player == null || player.getHandCardCount() <= TurnFlowService.MAX_HAND_SIZE) {
            return;
        }
        controller.ensureSessionActive();
        turnState.ensureTurnContext(player);
        if (turnState.phase() == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot force-discard.");
        }
        List<Card> chosen = player.chooseOverflowDiscardsTo(TurnFlowService.MAX_HAND_SIZE);
        if (player instanceof AIPlayer ai && ai.getPlayStrategy() instanceof AiChoiceAdvisor advisor) {
            controller.refreshAiDecisionContext();
            controller.attachAuxiliaryDecisionMementoForTrace();
            chosen = advisor.chooseOverflowDiscards(
                    ai, controller.getGameContext(), TurnFlowService.MAX_HAND_SIZE, chosen);
        }
        List<Card> discarded = player.discardSpecificFromHand(chosen);
        while (player.getHandCardCount() > TurnFlowService.MAX_HAND_SIZE) {
            discarded.addAll(player.discardOverflowTo(TurnFlowService.MAX_HAND_SIZE));
        }
        engine.discardMany(discarded);
        turnState.setMustDiscardOverflow(false);
        controller.pushSnapshot(controller.getCurrentSessionId(), "FORCE_DISCARD",
                player.getDisplayName() + " force-discarded "
                        + discarded.size() + " overflow card(s).");
    }
}