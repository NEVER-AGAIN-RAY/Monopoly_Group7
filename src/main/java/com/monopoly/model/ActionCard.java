package com.monopoly.model;

/**
 * 行动卡：打出后触发特殊效果（收租、偷牌等），效果链由控制器/引擎调度。
 */
public class ActionCard extends Card implements Payable {

    private final String effectCode;

    public ActionCard(String id, String name, String effectCode) {
        super(id, name);
        this.effectCode = effectCode;
    }

    public String getEffectCode() {
        return effectCode;
    }

    @Override
    public boolean canPlay(Player actor, GameContext context) {
        // 骨架：后续与目标合法性联动
        return true;
    }

    @Override
    public int getPaymentValue() {
        // 骨架：存入银行后的钱币面值等
        return 0;
    }
}
