package com.monopoly.model.effects;

/**
 * 【Strategy 策略模式——行动卡效果策略】
 * 所有行动卡效果的公共接口；每种效果（收租/偷牌/免租/双倍租/强制交换/债务催缴）
 * 各自实现此接口，并在 {@link ActionEffectDispatcher} 中与 effectCode 绑定。
 */
public interface ActionEffect {

    /**
     * 执行效果。
     *
     * @param ctx 包含施效者、目标、牌堆等完整上下文
     * @return 执行结果（成功/失败/被抵消）
     */
    ActionEffectResult execute(ActionEffectContext ctx);
}
