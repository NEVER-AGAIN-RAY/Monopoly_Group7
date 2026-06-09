package com.monopoly.dto;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Broadcast snapshot for STATE_UPDATE (public zones only).
 */
public class GameStateSnapshot {

    private String sessionId;
    private String phase;
    private String currentPlayerId;
    private String turnPhase;
    private int drawPileCount;
    private int discardPileCount;
    /** Monotonic session-local STATE_UPDATE sequence. */
    private long stateSequence;
    /** Player who must make the next meaningful decision, if any. */
    private String decisionPlayerId;
    /** Machine-readable decision kind: DRAW, PLAY, PAY_OR_JUST_SAY_NO, etc. */
    private String decisionKind;
    /** Short UI label for the current decision. */
    private String decisionLabel;
    /** Deadline for the current decision, or 0 when there is no countdown. */
    private long decisionDeadlineEpochMs;
    /** Number of cards already played in the current turn. */
    private int actionsUsedThisTurn;
    /** Number of card plays still available in the current turn. */
    private int actionsRemainingThisTurn;
    /** Cards that must be discarded before this turn can advance. */
    private int overflowDiscardCount;
    /** One-based round number for display. */
    private int roundNumber;
    /** Player currently asked to play Just Say No or pass during an effect-stack response. */
    private String pendingResponsePlayerId;
    private String pendingResponseRole;
    private long responseDeadlineEpochMs;
    private String pendingResponseHint;
    /** Structured public details about the action/rent currently awaiting Just Say No. */
    private JsonObject pendingResponseContext;
    private int effectStackDepth;
    /** First payment amount in M during the tenant response window; null outside that phase. */
    private Integer pendingPaymentAmountM;
    /** Last rule or operation error code; null when there is no current error. */
    private String lastErrorCode;
    /** Human-readable message for the last error; null when there is no current error. */
    private String lastErrorMessage;
    /** Epoch milliseconds for the last error; 0 when no error has been recorded. */
    private long lastErrorTimestampEpochMs;
    /** True after a normal win or a forced session end. */
    private boolean gameOver;
    /** Reason for a forced end, such as TIMEOUT; null for normal play or a normal win. */
    private String forceEndReason;
    /** True when the game is paused (HVM immediate / PVP after full vote). */
    private boolean paused;
    /** True when a PVP pause vote is in progress but not yet unanimous. */
    private boolean pausePending;
    /** Number of players who have acknowledged the pause request so far. */
    private int pauseAckCount;
    /** Total number of human players who must acknowledge a pause. */
    private int pauseHumanCount;
    /** Short summary of the latest visible action for clients and JSON logs. */
    private String lastActionSummary;
    /** Sequence number for the latest played-card event; 0 before any card has been played. */
    private long lastPlayedSequence;
    private String lastPlayedPlayerId;
    private String lastPlayedActionType;
    private JsonObject lastPlayedCard;
    private final List<PlayerPublicSummary> players = new ArrayList<>();

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getCurrentPlayerId() {
        return currentPlayerId;
    }

    public void setCurrentPlayerId(String currentPlayerId) {
        this.currentPlayerId = currentPlayerId;
    }

    public String getTurnPhase() {
        return turnPhase;
    }

    public void setTurnPhase(String turnPhase) {
        this.turnPhase = turnPhase;
    }

    public int getDrawPileCount() {
        return drawPileCount;
    }

    public void setDrawPileCount(int drawPileCount) {
        this.drawPileCount = drawPileCount;
    }

    public int getDiscardPileCount() {
        return discardPileCount;
    }

    public void setDiscardPileCount(int discardPileCount) {
        this.discardPileCount = discardPileCount;
    }

    public long getStateSequence() {
        return stateSequence;
    }

    public void setStateSequence(long stateSequence) {
        this.stateSequence = stateSequence;
    }

    public String getDecisionPlayerId() {
        return decisionPlayerId;
    }

    public void setDecisionPlayerId(String decisionPlayerId) {
        this.decisionPlayerId = decisionPlayerId;
    }

    public String getDecisionKind() {
        return decisionKind;
    }

    public void setDecisionKind(String decisionKind) {
        this.decisionKind = decisionKind;
    }

    public String getDecisionLabel() {
        return decisionLabel;
    }

    public void setDecisionLabel(String decisionLabel) {
        this.decisionLabel = decisionLabel;
    }

    public long getDecisionDeadlineEpochMs() {
        return decisionDeadlineEpochMs;
    }

    public void setDecisionDeadlineEpochMs(long decisionDeadlineEpochMs) {
        this.decisionDeadlineEpochMs = decisionDeadlineEpochMs;
    }

    public int getActionsUsedThisTurn() {
        return actionsUsedThisTurn;
    }

    public void setActionsUsedThisTurn(int actionsUsedThisTurn) {
        this.actionsUsedThisTurn = actionsUsedThisTurn;
    }

    public int getActionsRemainingThisTurn() {
        return actionsRemainingThisTurn;
    }

    public void setActionsRemainingThisTurn(int actionsRemainingThisTurn) {
        this.actionsRemainingThisTurn = actionsRemainingThisTurn;
    }

    public int getOverflowDiscardCount() {
        return overflowDiscardCount;
    }

    public void setOverflowDiscardCount(int overflowDiscardCount) {
        this.overflowDiscardCount = overflowDiscardCount;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public void setRoundNumber(int roundNumber) {
        this.roundNumber = roundNumber;
    }

    public String getPendingResponsePlayerId() {
        return pendingResponsePlayerId;
    }

    public void setPendingResponsePlayerId(String pendingResponsePlayerId) {
        this.pendingResponsePlayerId = pendingResponsePlayerId;
    }

    public String getPendingResponseRole() {
        return pendingResponseRole;
    }

    public void setPendingResponseRole(String pendingResponseRole) {
        this.pendingResponseRole = pendingResponseRole;
    }

    public long getResponseDeadlineEpochMs() {
        return responseDeadlineEpochMs;
    }

    public void setResponseDeadlineEpochMs(long responseDeadlineEpochMs) {
        this.responseDeadlineEpochMs = responseDeadlineEpochMs;
    }

    public String getPendingResponseHint() {
        return pendingResponseHint;
    }

    public void setPendingResponseHint(String pendingResponseHint) {
        this.pendingResponseHint = pendingResponseHint;
    }

    public JsonObject getPendingResponseContext() {
        return pendingResponseContext;
    }

    public void setPendingResponseContext(JsonObject pendingResponseContext) {
        this.pendingResponseContext = pendingResponseContext;
    }

    public int getEffectStackDepth() {
        return effectStackDepth;
    }

    public void setEffectStackDepth(int effectStackDepth) {
        this.effectStackDepth = effectStackDepth;
    }

    public Integer getPendingPaymentAmountM() {
        return pendingPaymentAmountM;
    }

    public void setPendingPaymentAmountM(Integer pendingPaymentAmountM) {
        this.pendingPaymentAmountM = pendingPaymentAmountM;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public long getLastErrorTimestampEpochMs() {
        return lastErrorTimestampEpochMs;
    }

    public void setLastErrorTimestampEpochMs(long lastErrorTimestampEpochMs) {
        this.lastErrorTimestampEpochMs = lastErrorTimestampEpochMs;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public void setGameOver(boolean gameOver) {
        this.gameOver = gameOver;
    }

    public String getForceEndReason() {
        return forceEndReason;
    }

    public void setForceEndReason(String forceEndReason) {
        this.forceEndReason = forceEndReason;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPausePending() {
        return pausePending;
    }

    public void setPausePending(boolean pausePending) {
        this.pausePending = pausePending;
    }

    public int getPauseAckCount() {
        return pauseAckCount;
    }

    public void setPauseAckCount(int pauseAckCount) {
        this.pauseAckCount = pauseAckCount;
    }

    public int getPauseHumanCount() {
        return pauseHumanCount;
    }

    public void setPauseHumanCount(int pauseHumanCount) {
        this.pauseHumanCount = pauseHumanCount;
    }

    public String getLastActionSummary() {
        return lastActionSummary;
    }

    public void setLastActionSummary(String lastActionSummary) {
        this.lastActionSummary = lastActionSummary;
    }

    public long getLastPlayedSequence() {
        return lastPlayedSequence;
    }

    public void setLastPlayedSequence(long lastPlayedSequence) {
        this.lastPlayedSequence = lastPlayedSequence;
    }

    public String getLastPlayedPlayerId() {
        return lastPlayedPlayerId;
    }

    public void setLastPlayedPlayerId(String lastPlayedPlayerId) {
        this.lastPlayedPlayerId = lastPlayedPlayerId;
    }

    public String getLastPlayedActionType() {
        return lastPlayedActionType;
    }

    public void setLastPlayedActionType(String lastPlayedActionType) {
        this.lastPlayedActionType = lastPlayedActionType;
    }

    public JsonObject getLastPlayedCard() {
        return lastPlayedCard;
    }

    public void setLastPlayedCard(JsonObject lastPlayedCard) {
        this.lastPlayedCard = lastPlayedCard;
    }

    public List<PlayerPublicSummary> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    public void clearPlayers() {
        players.clear();
    }

    public void addPlayerSummary(
            String playerId,
            String displayName,
            int handCount,
            int bankCount,
            int propertyCount,
            int actionZoneCount,
            int completePropertySets
    ) {
        addPlayerSummary(
                playerId,
                displayName,
                handCount,
                bankCount,
                propertyCount,
                actionZoneCount,
                completePropertySets,
                0,
                Collections.emptyList()
        );
    }

    public void addPlayerSummary(
            String playerId,
            String displayName,
            int handCount,
            int bankCount,
            int propertyCount,
            int actionZoneCount,
            int completePropertySets,
            int bankTotalValueM,
            List<PropertyColorCount> propertyCountsByColor
    ) {
        addPlayerSummary(
                playerId,
                displayName,
                handCount,
                bankCount,
                propertyCount,
                actionZoneCount,
                completePropertySets,
                bankTotalValueM,
                propertyCountsByColor,
                new JsonArray(),
                new JsonArray(),
                Collections.emptyList());
    }

    public void addPlayerSummary(
            String playerId,
            String displayName,
            int handCount,
            int bankCount,
            int propertyCount,
            int actionZoneCount,
            int completePropertySets,
            int bankTotalValueM,
            List<PropertyColorCount> propertyCountsByColor,
            JsonArray bankCardViews,
            JsonArray propertyZoneCardViews,
            List<PropertyColorProgress> propertyColorProgress
    ) {
        List<PropertyColorCount> safeColorCounts =
                propertyCountsByColor == null ? Collections.emptyList() : List.copyOf(propertyCountsByColor);
        JsonArray bankArr = bankCardViews != null ? bankCardViews : new JsonArray();
        JsonArray propArr = propertyZoneCardViews != null ? propertyZoneCardViews : new JsonArray();
        List<PropertyColorProgress> prog =
                propertyColorProgress == null ? Collections.emptyList() : List.copyOf(propertyColorProgress);
        players.add(new PlayerPublicSummary(
                playerId,
                displayName,
                handCount,
                bankCount,
                propertyCount,
                actionZoneCount,
                completePropertySets,
                bankTotalValueM,
                safeColorCounts,
                bankArr,
                propArr,
                prog
        ));
    }

    public static class PlayerPublicSummary {
        private final String playerId;
        private final String displayName;
        private final int handCount;
        private final int bankCount;
        private final int propertyCount;
        private final int actionZoneCount;
        private final int completePropertySets;
        private final int bankTotalValueM;
        private final List<PropertyColorCount> propertyCountsByColor;
        private final JsonArray bankCards;
        private final JsonArray propertyZoneCards;
        private final List<PropertyColorProgress> propertyColorProgress;

        public PlayerPublicSummary(
                String playerId,
                String displayName,
                int handCount,
                int bankCount,
                int propertyCount,
                int actionZoneCount,
                int completePropertySets,
                int bankTotalValueM,
                List<PropertyColorCount> propertyCountsByColor,
                JsonArray bankCards,
                JsonArray propertyZoneCards,
                List<PropertyColorProgress> propertyColorProgress
        ) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.handCount = handCount;
            this.bankCount = bankCount;
            this.propertyCount = propertyCount;
            this.actionZoneCount = actionZoneCount;
            this.completePropertySets = completePropertySets;
            this.bankTotalValueM = bankTotalValueM;
            this.propertyCountsByColor = propertyCountsByColor;
            this.bankCards = bankCards != null ? bankCards : new JsonArray();
            this.propertyZoneCards = propertyZoneCards != null ? propertyZoneCards : new JsonArray();
            this.propertyColorProgress = propertyColorProgress != null
                    ? propertyColorProgress : Collections.emptyList();
        }

        public String getPlayerId() {
            return playerId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getHandCount() {
            return handCount;
        }

        public int getBankCount() {
            return bankCount;
        }

        public int getPropertyCount() {
            return propertyCount;
        }

        public int getActionZoneCount() {
            return actionZoneCount;
        }

        public int getCompletePropertySets() {
            return completePropertySets;
        }

        public int getBankTotalValueM() {
            return bankTotalValueM;
        }

        public List<PropertyColorCount> getPropertyCountsByColor() {
            return propertyCountsByColor;
        }

        public JsonArray getBankCards() {
            return bankCards;
        }

        public JsonArray getPropertyZoneCards() {
            return propertyZoneCards;
        }

        public List<PropertyColorProgress> getPropertyColorProgress() {
            return propertyColorProgress;
        }
    }
}
