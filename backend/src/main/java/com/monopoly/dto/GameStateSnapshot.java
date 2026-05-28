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
    /** 等待打出免租/放弃的玩家（效果栈响应阶段） */
    private String pendingResponsePlayerId;
    private String pendingResponseRole;
    private long responseDeadlineEpochMs;
    private String pendingResponseHint;
    private int effectStackDepth;
    /** 承租人响应窗口内首笔应付租金（M），非该阶段为 null */
    private Integer pendingPaymentAmountM;
    /** 最近一次规则/操作错误码，无则为 null */
    private String lastErrorCode;
    /** 最近一次错误说明，无则为 null */
    private String lastErrorMessage;
    /** 最近一次错误时间（毫秒 epoch），无错误时为 0 */
    private long lastErrorTimestampEpochMs;
    /** 是否已结束对局（自然胜利或强制结束） */
    private boolean gameOver;
    /** 强制结束原因，如 TIMEOUT；非强制结束时为 null */
    private String forceEndReason;
    /** 最近一次操作的简述（供客户端 / JSON 展示），如摸牌、出牌、结束回合 */
    private String lastActionSummary;
    /** 最近一次出牌事件序号；没有出牌事件时为 0。 */
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
