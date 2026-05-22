package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.Player;

/**
 * Whether a card can be played in the current context.
 */
public interface Playable {

    /**
     * @param params play parameters from client or AI
     * @return true if legal to play now
     */
    boolean canPlay(Player actor, ActionParamContext params, GameContext context);
}
