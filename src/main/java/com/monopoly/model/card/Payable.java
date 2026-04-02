package com.monopoly.model.card;

/**
 * 可作为支付手段（银行、财产抵押等）的能力标记（领域接口）。
 */
public interface Payable {

    /**
     * @return 用于结算的货币等价值（具体换算在业务层实现）
     */
    int getPaymentValue();
}
