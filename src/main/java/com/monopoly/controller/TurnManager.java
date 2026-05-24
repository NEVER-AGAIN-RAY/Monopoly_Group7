package com.monopoly.controller;

import com.monopoly.model.player.Player;

import java.util.List;

/**
 * Turn order list and current player pointer.
 */
public class TurnManager {

    private List<Player> turnOrder;
    private int currentIndex;

    public void bindTurnOrder(List<Player> players) {
        this.turnOrder = players;
        this.currentIndex = 0;
    }

    public void setCurrentIndex(int index) {
        if (turnOrder == null || turnOrder.isEmpty()) {
            this.currentIndex = 0;
            return;
        }
        this.currentIndex = Math.floorMod(index, turnOrder.size());
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
