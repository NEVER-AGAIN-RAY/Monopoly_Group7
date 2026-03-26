package com.monopoly.model;

/**
 * 真人玩家：决策由客户端 UI 驱动，后端仅校验与落盘状态。
 */
public class HumanPlayer extends Player {

    public HumanPlayer(String playerId, String displayName) {
        super(playerId, displayName);
    }
}
