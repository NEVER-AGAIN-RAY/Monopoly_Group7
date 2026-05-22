package com.monopoly.model.effects;

/**
 * [Strategy] Action card effects; each effectCode maps to an implementation in ActionEffectDispatcher.
 */
public interface ActionEffect {

    /**
     * Runs the effect.
     *
     * @param ctx actor, targets, engine, and card ids
     * @return SUCCESS, FAILED, or COUNTERED
     */
    ActionEffectResult execute(ActionEffectContext ctx);
}
