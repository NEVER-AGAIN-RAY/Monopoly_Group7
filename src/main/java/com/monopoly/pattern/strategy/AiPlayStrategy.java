package com.monopoly.pattern.strategy;

import com.monopoly.model.AIPlayer;
import com.monopoly.model.GameContext;

/**
 * 【Strategy 策略模式】
 * 封装 AI 出牌/行动决策，可在运行时切换简单/普通/困难实现。
 */
public interface AiPlayStrategy {

    /**
     * 根据当前上下文决定下一步动作（骨架：无具体博弈逻辑）。
     */
    void decideNextAction(AIPlayer bot, GameContext context);
}
