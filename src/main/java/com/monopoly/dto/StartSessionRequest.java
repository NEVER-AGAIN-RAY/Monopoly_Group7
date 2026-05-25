package com.monopoly.dto;

/**
 * START_SESSION payload: players, HVM/PVP/LLM/AI_VS_AI, AI difficulty, first player.
 */
public class StartSessionRequest {

    private String sessionId;
    /** 2–5 */
    private int playerCount;
    /** HVM, PVP, LLM, or AI_VS_AI. */
    private String gameMode;
    /** EASY / NORMAL / HARD; used by HVM sessions. */
    private String aiDifficulty;
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

    public boolean isRandomizeFirstPlayer() {
        return randomizeFirstPlayer;
    }

    public void setRandomizeFirstPlayer(boolean randomizeFirstPlayer) {
        this.randomizeFirstPlayer = randomizeFirstPlayer;
    }
}
