package com.monopoly.model.player;

import com.monopoly.model.core.GameContext;

/**
 * 真人玩家：决策由客户端 UI 驱动，后端仅校验与落盘状态。
 */
public class HumanPlayer extends Player {

    public HumanPlayer(String playerId, String displayName) {
        super(playerId, displayName);
    }

    @Override
    public void requestPlayDecision(GameContext context) {
        // 骨架：真人由客户端 UI 驱动（后端只做校验/状态更新），因此无需 AI 决策逻辑
    }
}
