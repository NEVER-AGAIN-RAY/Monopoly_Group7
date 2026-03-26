package com.monopoly.model;

/**
 * 出牌/校验时所需的轻量上下文占位（避免在骨架阶段引入臃肿依赖）。
 * 后续可替换为完整的游戏会话快照。
 */
public class GameContext {
    // 预留：当前回合玩家、阶段、公开牌堆状态等
}
