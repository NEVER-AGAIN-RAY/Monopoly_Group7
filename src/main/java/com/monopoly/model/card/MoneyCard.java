package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.Player;

/**
 * 钱币卡：面值（M），可存入银行并用于支付租金。
 */
public class MoneyCard extends Card implements Payable {

    private final int valueM;

    public MoneyCard(String id, String name, int valueM) {
        super(id, name);
        if (valueM <= 0) {
            throw new IllegalArgumentException("钱币面值必须为正数");
        }
        this.valueM = valueM;
    }

    public int getValueM() {
        return valueM;
    }

    @Override
    public boolean canPlay(Player actor, ActionParamContext params, GameContext context) {
        return true;
    }

    @Override
    public int getPaymentValue() {
        return valueM;
    }
}
