package com.monopoly.pattern.strategy;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;

/**
 * Normal AI: deploy sets, target rich opponents; see AiHeuristics.
 */
public class NormalAiPlayStrategy implements AiPlayStrategy {

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        return AiHeuristics.tryPlayOneCard(AiStrategyProfile.NORMAL, bot, context, bridge);
    }
}
