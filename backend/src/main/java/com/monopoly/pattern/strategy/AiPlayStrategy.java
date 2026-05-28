package com.monopoly.pattern.strategy;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;

/**
 * [Strategy]
 * AI submits plays through AiGameBridge like the JavaFX client.
 */
public interface AiPlayStrategy {

    /**
     * Tries one legal play; false if nothing valid.
     *
     * @return true if a card was played
     */
    boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge);
}
