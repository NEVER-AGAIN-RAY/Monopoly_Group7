package com.monopoly.pattern.strategy;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;

/**
 * 【Strategy 策略模式】
 * AI 通过 {@link AiGameBridge#submitPlayAction} 模拟前端请求，与 Human 共用控制器入口。
 */
public interface AiPlayStrategy {

    /**
     * 尝试打出一张手牌（经统一入口）；无法决策或全部不合法时返回 false。
     *
     * @return 是否成功打出一张牌
     */
    boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge);
}
