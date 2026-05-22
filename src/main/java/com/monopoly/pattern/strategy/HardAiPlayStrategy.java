package com.monopoly.pattern.strategy;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;

/**
 * Hard AI: block leaders; see AiHeuristics.
 */
public class HardAiPlayStrategy implements AiPlayStrategy {

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        return AiHeuristics.tryPlayOneCard(AiStrategyProfile.HARD, bot, context, bridge);
    }
}
