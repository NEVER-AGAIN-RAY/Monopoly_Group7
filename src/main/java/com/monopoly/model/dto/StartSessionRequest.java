package com.monopoly.model.dto;

/**
 * 开局会话配置：2–5 人、人机/人人、AI 难度、是否随机先手（requirements §2.1.1a–b）。
 */
public class StartSessionRequest {

    private String sessionId;
    /** 2–5 */
    private int playerCount;
    /** {@code HVM} 人机 / {@code PVP} 人人 */
    private String gameMode;
    /** {@code EASY} / {@code NORMAL} / {@code HARD}，仅 HVM 使用 */
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
