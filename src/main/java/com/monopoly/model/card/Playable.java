package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.Player;

/**
 * 可打出/可参与回合行动的卡牌能力标记（领域接口）。
 */
public interface Playable {

    /**
     * @param params 来自客户端/AI 的行动参数；非行动类出牌可为 null
     * @return 是否可在当前上下文中被打出
     */
    boolean canPlay(Player actor, ActionParamContext params, GameContext context);
}
