package com.monopoly.model.effects;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps action card effectCode strings to ActionEffect implementations.
 * <p>
 * effectCode registry (uppercase):
 * <ul>
 *   <li>RENT           – Collect rent for a color from a target</li>
 *   <li>RENT_DUAL      – Dual-color rent (1v1 by default; rentDualChargesEachOtherPlayer charges each opponent in turn via effect stack)</li>
 *   <li>DOUBLE_RENT    – Double rent multiplier</li>
 *   <li>STEAL_PROPERTY – Steal one eligible property card (Sly Deal)</li>
 *   <li>FORCED_DEAL    – Forced property swap</li>
 *   <li>DEBT_COLLECTOR – Debt collector (flat 5M)</li>
 *   <li>RENT_WAIVER    – Rent waiver (Just Say No)</li>
 *   <li>PASS_GO       – Draw 2 extra cards (Pass Go)</li>
 *   <li>HOUSE / HOTEL – House/hotel on property set</li>
 *   <li>BIRTHDAY      – Each opponent pays 2M (Birthday)</li>
 *   <li>DEAL_BREAKER  – Steal complete set (Deal Breaker)</li>
 *   <li>EFFECT_PLACEHOLDER – Legacy placeholder, no effect</li>
 * </ul>
 */
public final class ActionEffectDispatcher {

    private static final Map<String, ActionEffect> REGISTRY = new HashMap<>();

    static {
        REGISTRY.put("RENT", new RentEffect());
        REGISTRY.put("RENT_DUAL", ctx -> ActionEffectResult.failed(
                "RENT_DUAL 由回合流程入效果栈，请勿走通用 dispatch。"));
        REGISTRY.put("DOUBLE_RENT", ctx -> ActionEffectResult.failed(
                "DOUBLE_RENT 由回合流程记录为下一张租金牌翻倍，请勿走通用 dispatch。"));
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
     * Dispatches by effectCode.
     *
     * @return result, or FAILED if unknown code
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
