package com.monopoly.model.player;

import com.monopoly.model.core.GameContext;
import com.monopoly.pattern.strategy.AiPlayStrategy;

/**
 * AI player with injected AiPlayStrategy.
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
     * Legacy hook; real AI runs in AiTurnService.
     */
    @Override
    public void requestPlayDecision(GameContext context) {
        // plays via AiTurnService + AiGameBridge
    }
}
