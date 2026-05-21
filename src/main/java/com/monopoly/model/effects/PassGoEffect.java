package com.monopoly.model.effects;

import com.monopoly.model.card.Card;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;

/** Pass Go: draw two extra cards from the draw pile into the actor's hand. */
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
