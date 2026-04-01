package com.monopoly.pattern.strategy;

import com.monopoly.model.AIPlayer;
import com.monopoly.model.AiGameBridge;
import com.monopoly.model.GameContext;

/**
 * 简单 AI：随机化候选顺序与部分目标，启发式较弱；实现见 {@link AiHeuristics}。
 */
public class EasyAiPlayStrategy implements AiPlayStrategy {

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        return AiHeuristics.tryPlayOneCard(AiStrategyProfile.EASY, bot, context, bridge);
    }
}
