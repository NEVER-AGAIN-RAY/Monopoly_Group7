package com.monopoly.controller;

import com.monopoly.model.player.Player;

import java.util.List;

/**
 * 回合管理：顺时针轮转、当前行动玩家查询（具体随机先手等在后续实现）。
 */
public class TurnManager {

    private List<Player> turnOrder;
    private int currentIndex;

    public void bindTurnOrder(List<Player> players) {
        this.turnOrder = players;
        this.currentIndex = 0;
    }

    public Player getCurrentPlayer() {
        if (turnOrder == null || turnOrder.isEmpty()) {
            return null;
        }
        return turnOrder.get(currentIndex);
    }

    /** 结束当前玩家回合，切换至下一位 */
    public void advanceTurn() {
        if (turnOrder == null || turnOrder.isEmpty()) {
            return;
        }
        currentIndex = (currentIndex + 1) % turnOrder.size();
    }
}
