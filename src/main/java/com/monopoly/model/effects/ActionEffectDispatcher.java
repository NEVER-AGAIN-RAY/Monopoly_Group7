package com.monopoly.model.effects;

import java.util.HashMap;
import java.util.Map;

/**
 * 行动卡效果分派器：将 {@link com.monopoly.model.ActionCard} 的 effectCode 映射到对应的
 * {@link ActionEffect} 实现，实现"数据驱动"的效果调度。
 * <p>
 * effectCode 规范（大写）：
 * <ul>
 *   <li>{@code RENT}           – 按颜色向目标收租</li>
 *   <li>{@code DOUBLE_RENT}    – 双倍租金</li>
 *   <li>{@code STEAL_PROPERTY} – 偷牌（Sly Deal，财产区/银行见 {@link StealCardEffect}）</li>
 *   <li>{@code FORCED_DEAL}    – 强制交换房产</li>
 *   <li>{@code DEBT_COLLECTOR} – 债务催缴（固定 5M）</li>
 *   <li>{@code RENT_WAIVER}    – 免租（Just Say No）</li>
 *   <li>{@code PASS_GO}       – 额外摸 2 张牌</li>
 *   <li>{@code HOUSE} / {@code HOTEL} – 财产区建筑升级</li>
 *   <li>{@code BIRTHDAY}      – 每位其他玩家支付 2M</li>
 *   <li>{@code DEAL_BREAKER}  – 夺取完整套（占位实现）</li>
 *   <li>{@code EFFECT_PLACEHOLDER} – 旧版占位牌，无效果</li>
 * </ul>
 */
public final class ActionEffectDispatcher {

    private static final Map<String, ActionEffect> REGISTRY = new HashMap<>();

    static {
        REGISTRY.put("RENT", new RentEffect());
        REGISTRY.put("DOUBLE_RENT", new DoubleRentEffect());
        REGISTRY.put("STEAL_PROPERTY", new StealCardEffect());
        REGISTRY.put("FORCED_DEAL", new ForcedDealEffect());
        REGISTRY.put("DEBT_COLLECTOR", new DebtCollectorEffect());
        REGISTRY.put("RENT_WAIVER", new RentWaiverEffect());
        REGISTRY.put("PASS_GO", new PassGoEffect());
        REGISTRY.put("HOUSE", new HouseEffect());
        REGISTRY.put("HOTEL", new HotelEffect());
        REGISTRY.put("BIRTHDAY", new BirthdayEffect());
        REGISTRY.put("DEAL_BREAKER", new DealBreakerEffect());
        REGISTRY.put("EFFECT_PLACEHOLDER", ctx -> ActionEffectResult.success("占位行动卡，无效果。"));
    }

    private ActionEffectDispatcher() {
    }

    /**
     * 根据 effectCode 执行对应效果。
     *
     * @return 效果结果；effectCode 未知则返回 FAILED
     */
    public static ActionEffectResult dispatch(String effectCode, ActionEffectContext ctx) {
        if (effectCode == null || effectCode.isBlank()) {
            return ActionEffectResult.failed("effectCode 为空，无法分派效果。");
        }
        ActionEffect effect = REGISTRY.get(effectCode.trim().toUpperCase());
        if (effect == null) {
            return ActionEffectResult.failed("未知 effectCode：" + effectCode);
        }
        return effect.execute(ctx);
    }

    /** 是否存在该 effectCode 对应的效果处理器。 */
    public static boolean isKnown(String effectCode) {
        if (effectCode == null) {
            return false;
        }
        return REGISTRY.containsKey(effectCode.trim().toUpperCase());
    }
}
