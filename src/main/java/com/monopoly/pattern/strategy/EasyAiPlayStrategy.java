package com.monopoly.pattern.strategy;

import com.monopoly.model.AIPlayer;
import com.monopoly.model.GameContext;

/**
 * 简单 AI：启发式占位（后续可实现保守出牌、优先存钱等）。
 */
public class EasyAiPlayStrategy implements AiPlayStrategy {

    @Override
    public void decideNextAction(AIPlayer bot, GameContext context) {
        // 骨架
    }
}
