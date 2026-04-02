package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.Player;

/**
 * 所有卡牌抽象父类：封装标识与展示信息，具体玩法由子类实现。
 */
public abstract class Card implements Playable {

    protected final String id;
    protected final String name;

    protected Card(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public abstract boolean canPlay(Player actor, ActionParamContext params, GameContext context);
}
