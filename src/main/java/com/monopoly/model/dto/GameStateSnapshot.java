package com.monopoly.model.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对外可序列化为 JSON 的游戏状态快照（骨架字段可逐步充实）。
 */
public class GameStateSnapshot {

    private String sessionId;
    private String phase;
    private String currentPlayerId;
    private String turnPhase;
    private int drawPileCount;
    private int discardPileCount;
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
        players.add(new PlayerPublicSummary(
                playerId,
                displayName,
                handCount,
                bankCount,
                propertyCount,
                actionZoneCount,
                completePropertySets
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

        public PlayerPublicSummary(
                String playerId,
                String displayName,
                int handCount,
                int bankCount,
                int propertyCount,
                int actionZoneCount,
                int completePropertySets
        ) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.handCount = handCount;
            this.bankCount = bankCount;
            this.propertyCount = propertyCount;
            this.actionZoneCount = actionZoneCount;
            this.completePropertySets = completePropertySets;
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
    }
}
