package com.monopoly.model.effects;

import com.monopoly.model.card.Card;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;

/**
 * Pass Go：从抽牌堆额外摸 2 张牌加入当前玩家手牌。
 */
public final class PassGoEffect implements ActionEffect {

    private static final int DRAW_COUNT = 2;

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        GameEngineSingleton engine = ctx.getEngine();
        if (actor == null || engine == null) {
            return ActionEffectResult.failed("Pass Go 效果上下文无效。");
        }
        int drawn = 0;
        for (int i = 0; i < DRAW_COUNT; i++) {
            Card c = engine.drawOne();
            if (c == null) {
                break;
            }
            actor.receiveCardToHand(c);
            drawn++;
        }
        return ActionEffectResult.success(
                "Pass Go：已摸 " + drawn + " 张牌（目标 2 张，牌堆不足时可能少于 2）。");
    }
}
