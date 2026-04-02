package com.monopoly.pattern.strategy;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;

/**
 * 普通 AI：优先部署凑套；收租/催债偏向银行厚的对手（假定更付得起）；阶段顺序见 {@link AiHeuristics}。
 */
public class NormalAiPlayStrategy implements AiPlayStrategy {

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        return AiHeuristics.tryPlayOneCard(AiStrategyProfile.NORMAL, bot, context, bridge);
    }
}
