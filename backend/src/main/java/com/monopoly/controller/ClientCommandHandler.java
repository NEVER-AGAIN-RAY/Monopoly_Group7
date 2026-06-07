package com.monopoly.controller;

import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.controller.ProtocolErrors.ProtocolValidationException;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.player.Player;

import java.util.Locale;
import java.util.Set;

import static com.monopoly.controller.ProtocolErrors.ERR_PLAY_ACTION_TYPE_INVALID;
import static com.monopoly.controller.ProtocolErrors.ERR_PLAY_ACTION_TYPE_REQUIRED;
import static com.monopoly.controller.ProtocolErrors.ERR_PLAY_ACTING_PLAYER_REQUIRED;
import static com.monopoly.controller.ProtocolErrors.ERR_PLAY_CARD_SELECTOR_REQUIRED;
import static com.monopoly.controller.ProtocolErrors.ERR_PLAY_HAND_INDEX_INVALID;
import static com.monopoly.controller.ProtocolErrors.ERR_PLAY_REQUEST_EMPTY;

final class ClientCommandHandler {

    private final GameController controller;

    ClientCommandHandler(GameController controller) {
        this.controller = controller;
    }

    void handleDrawCommand(int count) {
        try {
            controller.ensureNotPaused();
            controller.ensureSessionActive();
            controller.clearLastError();
            Player current = controller.requireCurrentPlayer();
            controller.drawCards(current, count);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                controller.recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    void handlePlayActionRequest(PlayActionRequest req) {
        try {
            controller.ensureNotPaused();
            controller.ensureSessionActive();
            controller.clearLastError();
            validatePlayActionRequest(req);
            String normalized = req.getActionType().trim().toUpperCase(Locale.ROOT);
            TurnFlowService turnFlow = controller.turnFlowService();

            if (turnFlow.phase() == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
                if ("RESPONSE_PASS".equals(normalized)) {
                    controller.effectStackOrchestrator().performResponsePass(
                            req.getActingPlayerId(), req.getPaymentCardIds());
                    return;
                }
                if ("ACTION".equals(normalized)) {
                    controller.effectStackOrchestrator().handleWaiverPlay(req);
                    return;
                }
                throw new IllegalStateException(
                        "During response window, only ACTION (waiver) or RESPONSE_PASS is allowed.");
            }

            if ("DISCARD".equals(normalized)) {
                handleDiscardRequest(req);
                return;
            }

            Player current = controller.requireCurrentPlayer();
            ActionParamContext params = ActionParamContext.fromPlayRequest(req);
            if ("ACTION".equals(normalized)) {
                turnFlow.handleActionCardCommand(params);
                return;
            }
            Card card = turnFlow.resolveCardInHand(
                    current, req.getCardId(), req.getHandIndex());
            turnFlow.playCard(current, card, normalized, params);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                controller.recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    ActionOptionsResult queryActionOptionsForHandCard(String playerId, String cardId) {
        try {
            controller.ensureNotPaused();
            controller.ensureSessionActive();
            controller.clearLastError();
            if (playerId == null || playerId.isBlank()) {
throw new IllegalArgumentException("playerId must not be blank.");
                }
                if (cardId == null || cardId.isBlank()) {
                    throw new IllegalArgumentException("cardId must not be blank.");
                }
                Player cur = controller.requireCurrentPlayer();
                if (!playerId.trim().equals(cur.getPlayerId())) {
                    throw new IllegalStateException("Only the current-turn player may query action options.");
                }
                TurnFlowService turnFlow = controller.turnFlowService();
                if (turnFlow.phase() != TurnFlowService.TurnPhase.PLAY) {
                    throw new IllegalStateException("Action options only available during PLAY phase.");
                }
                Card c = turnFlow.resolveCardInHand(cur, cardId, null);
                if (!(c instanceof ActionCard ac)) {
                    throw new IllegalArgumentException("The card is not an action card.");
            }
            return ActionOptionsService.build(
                    cur, ac, controller.getSessionPlayersView(),
                    controller.getEngine(), controller.getGameContext());
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                controller.recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    ActionOptionsResult queryPlayOptions(String playerId, String cardId, String actionType) {
        try {
            controller.ensureNotPaused();
            controller.ensureSessionActive();
            controller.clearLastError();
            if (playerId == null || playerId.isBlank()) {
throw new IllegalArgumentException("playerId must not be blank.");
                }
                if (cardId == null || cardId.isBlank()) {
                    throw new IllegalArgumentException("cardId must not be blank.");
                }
                if (actionType == null || actionType.isBlank()) {
                    throw new IllegalArgumentException("actionType must not be blank.");
                }
                Player cur = controller.requireCurrentPlayer();
                if (!playerId.trim().equals(cur.getPlayerId())) {
                    throw new IllegalStateException("Only the current-turn player may query play options.");
                }
                TurnFlowService turnFlow = controller.turnFlowService();
                if (turnFlow.phase() != TurnFlowService.TurnPhase.PLAY) {
                    throw new IllegalStateException("Play options only available during PLAY phase.");
            }
            Card c = turnFlow.resolveCardInHand(cur, cardId, null);
            return PlayOptionsService.build(
                    cur, c, actionType, controller.getSessionPlayersView(),
                    controller.getEngine(), controller.getGameContext());
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                controller.recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    void validatePlayActionRequest(PlayActionRequest req) {
        if (req == null) {
            throw new ProtocolValidationException(ERR_PLAY_REQUEST_EMPTY, "Play request must not be empty.");
        }
        if (req.getActionType() == null || req.getActionType().isBlank()) {
            throw new ProtocolValidationException(
                    ERR_PLAY_ACTION_TYPE_REQUIRED, "actionType must not be blank.");
        }
        String normalized = req.getActionType().trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = Set.of(
                "DEPLOY", "DEPOSIT", "ACTION", "DISCARD", "RESPONSE_PASS");
        if (!allowed.contains(normalized)) {
            throw new ProtocolValidationException(
                    ERR_PLAY_ACTION_TYPE_INVALID, "Unsupported actionType: " + req.getActionType());
        }
        if ("RESPONSE_PASS".equals(normalized)) {
            if (req.getActingPlayerId() == null || req.getActingPlayerId().isBlank()) {
                throw new ProtocolValidationException(
                        ERR_PLAY_ACTING_PLAYER_REQUIRED,
                        "RESPONSE_PASS requires actingPlayerId.");
            }
            return;
        }
        if (req.getCardId() == null || req.getCardId().isBlank()) {
            if (req.getHandIndex() == null) {
                throw new ProtocolValidationException(
                        ERR_PLAY_CARD_SELECTOR_REQUIRED, "Must provide cardId or handIndex.");
            }
            if (req.getHandIndex() < 0) {
                throw new ProtocolValidationException(
                        ERR_PLAY_HAND_INDEX_INVALID, "handIndex must be >= 0.");
            }
        }
    }

    void handlePlayCommand(int handIndex, String actionType) {
        controller.ensureSessionActive();
        PlayActionRequest r = new PlayActionRequest();
        r.setHandIndex(handIndex);
        r.setActionType(actionType);
        handlePlayActionRequest(r);
    }

    void handleEndTurnCommand() {
        try {
            controller.ensureSessionActive();
            controller.clearLastError();
            Player current = controller.requireCurrentPlayer();
            controller.endTurn(current);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                controller.recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    private void handleDiscardRequest(PlayActionRequest req) {
        Player current = controller.requireCurrentPlayer();
        Card card = controller.turnFlowService().resolveCardInHand(
                current, req.getCardId(), req.getHandIndex());
        controller.turnFlowService().discardFromHand(current, card);
    }
}
