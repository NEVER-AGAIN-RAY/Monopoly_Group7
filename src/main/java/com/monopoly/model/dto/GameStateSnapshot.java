package com.monopoly.model.dto;

/**
 * 对外可序列化为 JSON 的游戏状态快照（骨架字段可逐步充实）。
 */
public class GameStateSnapshot {

    private String sessionId;
    private String phase;

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
}
