package com.monopoly.model.effects;

/**
 * Just Say No: tenant/defender counters current effect; returns COUNTERED to stop the chain.
 */
public class RentWaiverEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        String actorName = ctx.getActor() != null ? ctx.getActor().getDisplayName() : "玩家";
        return ActionEffectResult.countered(actorName + " 打出免租牌，本次效果被抵消。");
    }
}
