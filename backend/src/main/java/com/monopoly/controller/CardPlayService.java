package com.monopoly.controller;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.Player;

final class CardPlayService {

    private final GameController controller;
    private final TurnState turnState;

    CardPlayService(GameController controller, TurnState turnState) {
        this.controller = controller;
        this.turnState = turnState;
    }

    void playCard(Player player, Card card, String actionType, ActionParamContext params) {
        if (player == null || card == null) {
            return;
        }
        controller.ensureSessionActive();
        turnState.ensureTurnContext(player);
        if (turnState.phase() == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot play cards.");
        }
        turnState.ensureNoPendingOverflowDiscard(player, "playing a card");
        turnState.ensureTurnActionAvailable();
        if (turnState.phase() != TurnFlowService.TurnPhase.PLAY) {
            throw new IllegalStateException("Not in PLAY phase; please draw first.");
        }
        if (!player.getHandCardsView().contains(card)) {
            throw new IllegalStateException("Card not in current player's hand; cannot play.");
        }
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType must not be blank.");
        }
        String normalizedActionType = actionType.trim().toUpperCase();

        if ("DEPOSIT".equals(normalizedActionType)) {
            turnState.incrementAction();
            player.depositToBank(card);
        } else if ("DEPLOY".equals(normalizedActionType)) {
            if (!(card instanceof PropertyCard)) {
                throw new IllegalArgumentException("DEPLOY requires a PropertyCard.");
            }
            if (card instanceof PropertyWildCard wild) {
                String requested = params != null ? TurnFlowService.blankToNull(params.getTargetColorKey()) : null;
                String current = wild.getAssignedColorKey();
                if (current != null && requested != null && !current.equalsIgnoreCase(requested)) {
                    throw new IllegalStateException("Wild property already assigned " + current + "; cannot change to " + requested + ".");
                }
                String assign = current != null ? current : requested;
                if (assign == null) {
                    throw new IllegalArgumentException("Deploying a wild property requires targetColorKey.");
                }
                wild.setAssignedColorKey(assign);
            }
            turnState.incrementAction();
            player.deployProperty((PropertyCard) card);
        } else if ("ACTION".equals(normalizedActionType)) {
            // ACTION cards must run their effect via ActionCardPlayService.handleActionCardCommand;
            // routing one here would consume the card and an action without ever firing the effect.
            throw new IllegalArgumentException(
                    "ACTION cards must be played through handleActionCardCommand, not playCard.");
        } else {
            throw new IllegalArgumentException("Unknown actionType: " + actionType);
        }

        if (turnState.actionCount() >= TurnFlowService.MAX_ACTIONS_PER_TURN) {
            turnState.markEndTurn();
        }

        controller.pushSnapshot(
                controller.getCurrentSessionId(),
                normalizedActionType,
                player.getDisplayName() + " played " + normalizedActionType + " (" + card.getName() + ").",
                player,
                card,
                normalizedActionType);
    }
}
