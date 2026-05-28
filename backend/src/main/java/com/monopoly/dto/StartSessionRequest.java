package com.monopoly.dto;

import java.util.List;

/**
 * START_SESSION payload: players, HVM/PVP/LLM/AI_VS_AI/CUSTOM, AI difficulty, first player.
 */
public class StartSessionRequest {

    private String sessionId;
    /** 2–5 */
    private int playerCount;
    /** HVM/PVP/LLM/AI_VS_AI/CUSTOM. */
    private String gameMode;
    /** EASY / NORMAL / HARD; used by HVM sessions. */
    private String aiDifficulty;
    /** CUSTOM: comma-separated seats, e.g. human,human,llm,lookahead. */
    private String customLineup;
    /** CUSTOM: preferred structured form; same role names as customLineup. */
    private List<String> playerRoles;
    /** Optional display names by seat, mainly for room-lobby CUSTOM games. */
    private List<String> displayNames;
    private boolean randomizeFirstPlayer;

    public StartSessionRequest() {
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }

    public String getGameMode() {
        return gameMode;
    }

    public void setGameMode(String gameMode) {
        this.gameMode = gameMode;
    }

    public String getAiDifficulty() {
        return aiDifficulty;
    }

    public void setAiDifficulty(String aiDifficulty) {
        this.aiDifficulty = aiDifficulty;
    }

    public String getCustomLineup() {
        return customLineup;
    }

    public void setCustomLineup(String customLineup) {
        this.customLineup = customLineup;
    }

    public List<String> getPlayerRoles() {
        return playerRoles;
    }

    public void setPlayerRoles(List<String> playerRoles) {
        this.playerRoles = playerRoles;
    }

    public List<String> getDisplayNames() {
        return displayNames;
    }

    public void setDisplayNames(List<String> displayNames) {
        this.displayNames = displayNames;
    }

    public boolean isRandomizeFirstPlayer() {
        return randomizeFirstPlayer;
    }

    public void setRandomizeFirstPlayer(boolean randomizeFirstPlayer) {
        this.randomizeFirstPlayer = randomizeFirstPlayer;
    }
}
