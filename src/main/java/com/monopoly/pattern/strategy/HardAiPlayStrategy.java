package com.monopoly.pattern.strategy;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;

/**
 * 困难 AI：优先压制高威胁对手（完整套、银行、财产）；阶段顺序与目标选择见 {@link AiHeuristics}。
 */
public class HardAiPlayStrategy implements AiPlayStrategy {

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        return AiHeuristics.tryPlayOneCard(AiStrategyProfile.HARD, bot, context, bridge);
    }
}
