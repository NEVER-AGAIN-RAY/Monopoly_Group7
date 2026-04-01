package com.monopoly.pattern.strategy;

/**
 * AI 难度画像：供 {@link AiHeuristics} 在相同公开信息下采用不同启发式与阶段顺序。
 */
public enum AiStrategyProfile {
    /** 较弱：随机打乱候选顺序，万能色固定性弱 */
    EASY,
    /** 优先凑套与部署；收租/催债选银行最厚（假定更付得起） */
    NORMAL,
    /** 优先针对高威胁对手（完整套+银行+财产）；攻击性强 */
    HARD
}
