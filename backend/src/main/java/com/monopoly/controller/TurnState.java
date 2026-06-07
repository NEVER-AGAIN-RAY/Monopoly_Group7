package com.monopoly.controller;

import com.monopoly.model.player.Player;

final class TurnState {

    private String currentPlayerId;
    private int actionCount;
    private TurnFlowService.TurnPhase phase;
    private boolean mustDiscardOverflow;

    TurnState() {
        this.phase = TurnFlowService.TurnPhase.DRAW;
    }

    String currentPlayerId() {
        return currentPlayerId;
    }

    int actionCount() {
        return actionCount;
    }

    TurnFlowService.TurnPhase phase() {
        return phase;
    }

    boolean mustDiscardOverflow() {
        return mustDiscardOverflow;
    }

    void initForSession(String firstPlayerId) {
        this.currentPlayerId = firstPlayerId;
        this.actionCount = 0;
        this.phase = TurnFlowService.TurnPhase.DRAW;
        this.mustDiscardOverflow = false;
    }

    void enterPlay() {
        this.phase = TurnFlowService.TurnPhase.PLAY;
    }

    void enterWaitingForResponse() {
        this.phase = TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE;
    }

    void resumeToPlayOrEnd(int actionCountThreshold) {
        this.phase = TurnFlowService.TurnPhase.PLAY;
        if (actionCountThreshold >= TurnFlowService.MAX_ACTIONS_PER_TURN) {
            this.phase = TurnFlowService.TurnPhase.END_TURN;
        }
    }

    void markEndTurn() {
        this.phase = TurnFlowService.TurnPhase.END_TURN;
    }

    void incrementAction() {
        this.actionCount++;
    }

    void decrementAction() {
        this.actionCount--;
    }

    void setActionCount(int value) {
        this.actionCount = value;
    }

    void setMustDiscardOverflow(boolean value) {
        this.mustDiscardOverflow = value;
    }

    void ensureTurnContext(Player player) {
        String pid = player.getPlayerId();
        if (currentPlayerId == null) {
            currentPlayerId = pid;
            actionCount = 0;
            phase = TurnFlowService.TurnPhase.DRAW;
            mustDiscardOverflow = false;
            return;
        }
        if (!currentPlayerId.equals(pid)) {
            throw new IllegalStateException("Not player " + pid + "'s turn.");
        }
    }

    void ensureNoPendingOverflowDiscard(Player player, String attemptedAction) {
        if (!mustDiscardOverflow) {
            return;
        }
        if (player != null && player.getHandCardCount() <= TurnFlowService.MAX_HAND_SIZE) {
            mustDiscardOverflow = false;
            return;
        }
        throw new IllegalStateException(
                "Must discard down to 7 cards before " + attemptedAction + ".");
    }

    void ensureTurnActionAvailable() {
        if (actionCount >= TurnFlowService.MAX_ACTIONS_PER_TURN) {
            throw new IllegalStateException("Maximum 3 actions per turn reached.");
        }
    }
}