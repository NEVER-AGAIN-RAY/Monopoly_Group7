package com.monopoly.model;

/**
 * 可打出/可参与回合行动的卡牌能力标记（领域接口）。
 */
public interface Playable {

    /**
     * @return 是否可在当前上下文中被打出（具体规则由后续实现校验）
     */
    boolean canPlay(Player actor, GameContext context);
}
