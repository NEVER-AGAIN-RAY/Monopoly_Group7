package com.monopoly.model.core;

import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 出牌/校验时所需的上下文：玩家列表、效果结算栈与响应状态机（免租连锁）。
 */
public class GameContext {

    private List<Player> players = List.of();
    private final List<EffectStackEntry> effectStack = new ArrayList<>();
    private StackResponseState responseState;
    /** 非空时表示正在进行 RENT_DUAL 多承租人依次收租。 */
    private RentChargeSequence rentChargeSequence;

    public void bindPlayers(List<Player> players) {
        this.players = players == null ? List.of() : Collections.unmodifiableList(players);
    }

    public List<Player> getPlayers() {
        return players;
    }

    /** @return 与 {@code playerId} 匹配的玩家，找不到则 null */
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

    /** 栈底方向第一个收租类条目（被第一张免租针对的收租） */
    public String findBottomRentEntryId() {
        for (EffectStackEntry e : effectStack) {
            if (e.isRentLike()) {
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
