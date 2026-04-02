package com.monopoly.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话中一名玩家的简化存档视图（身份 + 各分区牌张快照）。
 */
public final class SessionPlayerMemento {

    public enum PlayerKind {
        HUMAN,
        AI
    }

    private String playerId;
    private String displayName;
    private PlayerKind playerKind;
    /** 仅 AI 时有意义：EASY / NORMAL / HARD */
    private String aiDifficulty;

    private List<PersistedCard> handCards = new ArrayList<>();
    private List<PersistedCard> bankCards = new ArrayList<>();
    private List<PersistedCard> propertyCards = new ArrayList<>();
    private List<PersistedCard> actionZoneCards = new ArrayList<>();

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public PlayerKind getPlayerKind() {
        return playerKind;
    }

    public void setPlayerKind(PlayerKind playerKind) {
        this.playerKind = playerKind;
    }

    public String getAiDifficulty() {
        return aiDifficulty;
    }

    public void setAiDifficulty(String aiDifficulty) {
        this.aiDifficulty = aiDifficulty;
    }

    public List<PersistedCard> getHandCards() {
        return handCards;
    }

    public void setHandCards(List<PersistedCard> handCards) {
        this.handCards = handCards != null ? handCards : new ArrayList<>();
    }

    public List<PersistedCard> getBankCards() {
        return bankCards;
    }

    public void setBankCards(List<PersistedCard> bankCards) {
        this.bankCards = bankCards != null ? bankCards : new ArrayList<>();
    }

    public List<PersistedCard> getPropertyCards() {
        return propertyCards;
    }

    public void setPropertyCards(List<PersistedCard> propertyCards) {
        this.propertyCards = propertyCards != null ? propertyCards : new ArrayList<>();
    }

    public List<PersistedCard> getActionZoneCards() {
        return actionZoneCards;
    }

    public void setActionZoneCards(List<PersistedCard> actionZoneCards) {
        this.actionZoneCards = actionZoneCards != null ? actionZoneCards : new ArrayList<>();
    }
}
