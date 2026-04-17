package com.monopoly.model.settlement;

import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 租金支付：仅允许使用银行堆中的可支付牌 + 财产区房产（退回弃牌堆），禁止用手牌支付。
 * <p>
 * 规则（requirements）：
 * <ul>
 *   <li>找零不退：所选牌总面值可以大于应付额，全部按约定转移</li>
 *   <li>银行牌归收租方银行；房产牌进入公共弃牌堆</li>
 *   <li>若银行与财产区可凑出的最大面值仍小于应付额，则本次支付失败，不发生任何转移</li>
 * </ul>
 * 选牌策略：先按面值升序用尽银行牌，再按面值升序动用房产，直到累计面值不低于应付额（贪心凑足额）。
 */
public final class PaymentSettlement {

    public enum Status {
        SUCCESS,
        FAILED
    }

    public static final class Result {
        private final Status status;
        private final int amountDue;
        private final int amountPaid;
        private final String message;

        public Result(Status status, int amountDue, int amountPaid, String message) {
            this.status = status;
            this.amountDue = amountDue;
            this.amountPaid = amountPaid;
            this.message = message;
        }

        public Status getStatus() {
            return status;
        }

        public int getAmountDue() {
            return amountDue;
        }

        public int getAmountPaid() {
            return amountPaid;
        }

        public String getMessage() {
            return message;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }
    }

    private PaymentSettlement() {
    }

    public static Result settle(Player debtor, Player creditor, int amountDue, GameEngineSingleton engine) {
        if (debtor == null || creditor == null || engine == null) {
            return new Result(Status.FAILED, amountDue, 0, "参数无效");
        }
        if (amountDue <= 0) {
            return new Result(Status.SUCCESS, amountDue, 0, "无需支付");
        }

        List<Card> bank = new ArrayList<>(debtor.getBankCardsView());
        bank.sort(Comparator.comparingInt(PayableCards::valueOf));

        List<PropertyCard> props = new ArrayList<>(debtor.getPropertyCardsView());
        props.sort(Comparator.comparingInt(p -> PayableCards.valueOf(p)));

        List<Card> chosen = new ArrayList<>();
        int sum = 0;

        for (Card c : bank) {
            if (sum >= amountDue) {
                break;
            }
            chosen.add(c);
            sum += PayableCards.valueOf(c);
        }
        if (sum < amountDue) {
            for (PropertyCard p : props) {
                if (sum >= amountDue) {
                    break;
                }
                chosen.add(p);
                sum += PayableCards.valueOf(p);
            }
        }

        if (sum < amountDue) {
            return new Result(Status.FAILED, amountDue, sum,
                    "资产不足：银行与财产区可支付最大为 " + sum + "M，应付 " + amountDue + "M");
        }

        return transferChosen(debtor, creditor, amountDue, chosen, sum, engine);
    }

    /**
     * 玩家指定支付牌（须均在债务人银行或财产区；总面值 ≥ 应付；找零不退）。
     */
    public static Result settleWithExplicitCards(
            Player debtor,
            Player creditor,
            int amountDue,
            List<String> cardIds,
            GameEngineSingleton engine) {
        if (debtor == null || creditor == null || engine == null) {
            return new Result(Status.FAILED, amountDue, 0, "参数无效");
        }
        if (amountDue <= 0) {
            return new Result(Status.SUCCESS, amountDue, 0, "无需支付");
        }
        if (cardIds == null || cardIds.isEmpty()) {
            return settle(debtor, creditor, amountDue, engine);
        }
        Set<String> uniq = new LinkedHashSet<>();
        for (String id : cardIds) {
            if (id == null || id.isBlank()) {
                return new Result(Status.FAILED, amountDue, 0, "paymentCardIds 含空 id");
            }
            if (!uniq.add(id.trim())) {
                return new Result(Status.FAILED, amountDue, 0, "paymentCardIds 含重复 id：" + id);
            }
        }
        List<Card> chosen = new ArrayList<>();
        for (String id : uniq) {
            Card c = findPayableInBankOrProperty(debtor, id);
            if (c == null) {
                return new Result(Status.FAILED, amountDue, 0, "找不到可支付牌：" + id);
            }
            chosen.add(c);
        }
        int sum = 0;
        for (Card c : chosen) {
            sum += PayableCards.valueOf(c);
        }
        if (sum < amountDue) {
            return new Result(Status.FAILED, amountDue, sum,
                    "所选牌合计 " + sum + "M，低于应付 " + amountDue + "M");
        }
        return transferChosen(debtor, creditor, amountDue, chosen, sum, engine);
    }

    /**
     * 在修改状态前校验承租人选择的支付牌是否足以支付首笔租金（效果栈结算前调用）。
     */
    public static void validateExplicitChoice(Player debtor, int amountDue, List<String> cardIds) {
        if (debtor == null || amountDue <= 0 || cardIds == null || cardIds.isEmpty()) {
            return;
        }
        Result r = dryRunExplicit(debtor, amountDue, cardIds);
        if (!r.isSuccess()) {
            throw new IllegalArgumentException(r.getMessage());
        }
    }

    private static Result dryRunExplicit(Player debtor, int amountDue, List<String> cardIds) {
        Set<String> uniq = new LinkedHashSet<>();
        for (String id : cardIds) {
            if (id == null || id.isBlank()) {
                return new Result(Status.FAILED, amountDue, 0, "paymentCardIds 含空 id");
            }
            if (!uniq.add(id.trim())) {
                return new Result(Status.FAILED, amountDue, 0, "paymentCardIds 含重复 id");
            }
        }
        int sum = 0;
        for (String id : uniq) {
            Card c = findPayableInBankOrProperty(debtor, id);
            if (c == null) {
                return new Result(Status.FAILED, amountDue, 0, "找不到可支付牌：" + id);
            }
            sum += PayableCards.valueOf(c);
        }
        if (sum < amountDue) {
            return new Result(Status.FAILED, amountDue, sum,
                    "所选牌合计 " + sum + "M，低于应付 " + amountDue + "M");
        }
        return new Result(Status.SUCCESS, amountDue, sum, "ok");
    }

    private static Card findPayableInBankOrProperty(Player debtor, String id) {
        for (Card c : debtor.getBankCardsView()) {
            if (c != null && id.equals(c.getId())) {
                return c;
            }
        }
        for (PropertyCard p : debtor.getPropertyCardsView()) {
            if (p != null && id.equals(p.getId())) {
                return p;
            }
        }
        return null;
    }

    private static Result transferChosen(
            Player debtor,
            Player creditor,
            int amountDue,
            List<Card> chosen,
            int sum,
            GameEngineSingleton engine) {
        for (Card c : chosen) {
            if (c instanceof PropertyCard pc) {
                if (!debtor.removePropertyCard(pc)) {
                    return new Result(Status.FAILED, amountDue, 0, "状态不一致：无法移除房产牌");
                }
                engine.discard(pc);
            } else {
                if (!debtor.removeFromBank(c)) {
                    return new Result(Status.FAILED, amountDue, 0, "状态不一致：无法移除银行牌");
                }
                creditor.addToBank(c);
            }
        }

        return new Result(Status.SUCCESS, amountDue, sum,
                "支付成功：付出 " + sum + "M（应付 " + amountDue + "M，找零不退）");
    }
}
