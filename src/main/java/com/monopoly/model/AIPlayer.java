package com.monopoly.model;

import com.monopoly.pattern.strategy.AiPlayStrategy;

/**
 * AI 玩家：通过策略模式注入不同难度的出牌决策（具体算法在策略类中实现）。
 */
public class AIPlayer extends Player {

    private AiPlayStrategy playStrategy;

    public AIPlayer(String playerId, String displayName, AiPlayStrategy initialStrategy) {
        super(playerId, displayName);
        this.playStrategy = initialStrategy;
    }

    public void setPlayStrategy(AiPlayStrategy playStrategy) {
        this.playStrategy = playStrategy;
    }

    public AiPlayStrategy getPlayStrategy() {
        return playStrategy;
    }

    /**
     * 由回合控制器在 AI 回合调用，骨架阶段仅占位。
     */
    @Override
    public void requestPlayDecision(GameContext context) {
        // 实际出牌由 GameController.executeAiTurn 调用 AiPlayStrategy.tryPlayOneCard + AiGameBridge 驱动
    }
}
