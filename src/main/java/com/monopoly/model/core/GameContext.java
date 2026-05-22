package com.monopoly.model.core;

import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Session context: players, effect stack, rent-response state.
 */
public class GameContext {

    private List<Player> players = List.of();
    private final List<EffectStackEntry> effectStack = new ArrayList<>();
    private StackResponseState responseState;
    /** Set during RENT_DUAL multi-tenant collection. */
    private RentChargeSequence rentChargeSequence;

    public void bindPlayers(List<Player> players) {
        this.players = players == null ? List.of() : Collections.unmodifiableList(players);
    }

    public List<Player> getPlayers() {
        return players;
    }

    /** @return player with id, or null */
    public Player findPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        for (Player p : players) {
            if (playerId.equals(p.getPlayerId())) {
                return p;
            }
        }
        return null;
    }

    public List<EffectStackEntry> getEffectStackView() {
        return Collections.unmodifiableList(effectStack);
    }

    public void pushEffect(EffectStackEntry entry) {
        if (entry != null) {
            effectStack.add(entry);
        }
    }

    public void clearEffectStack() {
        effectStack.clear();
        responseState = null;
    }

    public RentChargeSequence getRentChargeSequence() {
        return rentChargeSequence;
    }

    public void setRentChargeSequence(RentChargeSequence rentChargeSequence) {
        this.rentChargeSequence = rentChargeSequence;
    }

    public void clearRentChargeSequence() {
        this.rentChargeSequence = null;
    }

    public StackResponseState getResponseState() {
        return responseState;
    }

    public void setResponseState(StackResponseState responseState) {
        this.responseState = responseState;
    }

    /** First rent entry from the bottom of the stack */
    public String findBottomRentEntryId() {
        for (EffectStackEntry e : effectStack) {
            if (e.isRentLike()) {
                return e.getId();
            }
        }
        return null;
    }

    /** 栈底方向第一个可被 Just Say No 抵消的非收租行动。 */
    public String findBottomActionEntryId() {
        for (EffectStackEntry e : effectStack) {
            if (e.isActionLike()) {
                return e.getId();
            }
        }
        return null;
    }

    public EffectStackEntry peekTopEffect() {
        if (effectStack.isEmpty()) {
            return null;
        }
        return effectStack.get(effectStack.size() - 1);
    }

    public boolean isAwaitingResponseFrom(String playerId) {
        return responseState != null
                && playerId != null
                && playerId.equals(responseState.getAwaitingPlayerId());
    }
}
