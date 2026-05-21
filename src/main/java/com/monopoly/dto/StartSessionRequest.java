package com.monopoly.dto;

/**
 * START_SESSION payload: players, HVM/PVP, AI difficulty, first player.
 */
public class StartSessionRequest {

    private String sessionId;
    /** 2–5 */
    private int playerCount;
    /** HVM 人机 / PVP 人人 */
    private String gameMode;
    /** EASY / NORMAL / HARD，仅 HVM 使用 */
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
