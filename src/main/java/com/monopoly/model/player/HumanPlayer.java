package com.monopoly.model.player;

import com.monopoly.model.core.GameContext;

/**
 * Human player: client sends actions; server validates.
 */
public class HumanPlayer extends Player {

    public HumanPlayer(String playerId, String displayName) {
        super(playerId, displayName);
    }

    @Override
    public void requestPlayDecision(GameContext context) {
        // humans: no server-side decision logic
    }
}
