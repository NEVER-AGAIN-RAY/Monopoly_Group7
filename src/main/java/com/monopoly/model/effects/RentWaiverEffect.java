package com.monopoly.model.effects;

/**
 * 免租效果（Just Say No）：由被收租/被偷牌方打出，抵消当前针对自己的行动卡效果。
 * 在 {@link ActionEffectDispatcher} 中以 {@link ActionEffectResult.Status#COUNTERED} 标记，
 * 控制器收到后中断正在执行的效果链。
 */
public class RentWaiverEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        String actorName = ctx.getActor() != null ? ctx.getActor().getDisplayName() : "玩家";
        return ActionEffectResult.countered(actorName + " 打出免租牌，本次效果被抵消。");
    }
}
