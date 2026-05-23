package com.monopoly.model.settlement;

import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rent payment from bank + property only; no change; optimized card selection.
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

        PaymentChoice choice = chooseAutomaticPayment(debtor, amountDue);
        List<Card> chosen = choice.cards();
        int sum = choice.amountPaid();

        return transferChosen(debtor, creditor, amountDue, chosen, sum, engine);
    }

    /**
     * Explicit card ids for payment (tenant pass).
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
            int totalPayable = totalPayableValue(debtor);
            if (sum < totalPayable) {
                return new Result(Status.FAILED, amountDue, sum,
                        "所选牌合计 " + sum + "M，低于应付 " + amountDue
                                + "M，且未付尽可支付资产 " + totalPayable + "M");
            }
        }
        return transferChosen(debtor, creditor, amountDue, chosen, sum, engine);
    }

    /**
     * Validates tenant payment choice before stack resolution.
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

    public static int estimateAutomaticAmountPaid(Player debtor, int amountDue) {
        if (debtor == null || amountDue <= 0) {
            return 0;
        }
        return chooseAutomaticPayment(debtor, amountDue).amountPaid();
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
            int totalPayable = totalPayableValue(debtor);
            if (sum < totalPayable) {
                return new Result(Status.FAILED, amountDue, sum,
                        "所选牌合计 " + sum + "M，低于应付 " + amountDue
                                + "M，且未付尽可支付资产 " + totalPayable + "M");
            }
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

    private static int totalPayableValue(Player debtor) {
        int total = 0;
        for (Card c : debtor.getBankCardsView()) {
            total += PayableCards.valueOf(c);
        }
        for (PropertyCard p : debtor.getPropertyCardsView()) {
            total += PayableCards.valueOf(p);
        }
        return total;
    }

    /**
     * Automatic payment policy:
     * <ol>
     *   <li>If bank cards can cover the bill, never sacrifice property cards.</li>
     *   <li>Within the eligible cards, minimize overpayment, then card count.</li>
     *   <li>When bank cannot cover the bill, add the least damaging property combination.</li>
     * </ol>
     */
    static PaymentChoice chooseAutomaticPayment(Player debtor, int amountDue) {
        List<PayOption> bank = new ArrayList<>();
        int bankTotal = 0;
        for (Card c : debtor.getBankCardsView()) {
            int v = PayableCards.valueOf(c);
            if (v <= 0) {
                continue;
            }
            bank.add(new PayOption(c, v, false));
            bankTotal += v;
        }

        List<PayOption> eligible = new ArrayList<>(bank);
        if (bankTotal < amountDue) {
            for (PropertyCard p : debtor.getPropertyCardsView()) {
                int v = PayableCards.valueOf(p);
                if (v <= 0) {
                    continue;
                }
                eligible.add(new PayOption(p, v, true));
            }
        }

        if (eligible.isEmpty()) {
            return new PaymentChoice(List.of(), 0);
        }

        int total = 0;
        for (PayOption option : eligible) {
            total += option.value();
        }
        if (total < amountDue) {
            List<Card> all = new ArrayList<>();
            for (PayOption option : eligible) {
                all.add(option.card());
            }
            return new PaymentChoice(List.copyOf(all), total);
        }

        int cap = total;
        ChoiceState[] dp = new ChoiceState[cap + 1];
        dp[0] = ChoiceState.empty();
        for (PayOption option : eligible) {
            for (int sum = cap; sum >= 0; sum--) {
                ChoiceState prev = dp[sum];
                if (prev == null) {
                    continue;
                }
                int nextSum = sum + option.value();
                if (nextSum > cap) {
                    continue;
                }
                ChoiceState next = prev.add(option);
                if (dp[nextSum] == null || compareChoice(nextSum, next, nextSum, dp[nextSum]) < 0) {
                    dp[nextSum] = next;
                }
            }
        }

        int bestSum = -1;
        ChoiceState best = null;
        for (int sum = amountDue; sum <= cap; sum++) {
            ChoiceState candidate = dp[sum];
            if (candidate == null) {
                continue;
            }
            if (best == null || compareChoice(sum, candidate, bestSum, best) < 0) {
                bestSum = sum;
                best = candidate;
            }
        }
        if (best == null) {
            return new PaymentChoice(List.of(), total);
        }
        return new PaymentChoice(best.cards(), bestSum);
    }

    private static int compareChoice(int amountA, ChoiceState a, int amountB, ChoiceState b) {
        int c = Integer.compare(amountA, amountB);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(a.propertyCount(), b.propertyCount());
        if (c != 0) {
            return c;
        }
        c = Integer.compare(a.propertyValue(), b.propertyValue());
        if (c != 0) {
            return c;
        }
        c = Integer.compare(a.cardCount(), b.cardCount());
        if (c != 0) {
            return c;
        }
        return Integer.compare(a.bankValue(), b.bankValue());
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
                creditor.receiveCardToHand(pc);
            } else {
                if (!debtor.removeFromBank(c)) {
                    return new Result(Status.FAILED, amountDue, 0, "状态不一致：无法移除银行牌");
                }
                creditor.receiveCardToHand(c);
            }
        }

        if (sum < amountDue) {
            return new Result(Status.SUCCESS, amountDue, sum,
                    "资产不足：已付尽可支付资产 " + sum + "M（应付 "
                            + amountDue + "M），收款方收入手牌");
        }
        return new Result(Status.SUCCESS, amountDue, sum,
                "支付成功：付出 " + sum + "M（应付 " + amountDue + "M，找零不退），收款方收入手牌");
    }

    record PaymentChoice(List<Card> cards, int amountPaid) {
    }

    private record PayOption(Card card, int value, boolean property) {
    }

    private record ChoiceState(
            List<Card> cards,
            int cardCount,
            int bankValue,
            int propertyCount,
            int propertyValue) {

        static ChoiceState empty() {
            return new ChoiceState(List.of(), 0, 0, 0, 0);
        }

        ChoiceState add(PayOption option) {
            List<Card> next = new ArrayList<>(cards);
            next.add(option.card());
            return new ChoiceState(
                    List.copyOf(next),
                    cardCount + 1,
                    bankValue + (option.property() ? 0 : option.value()),
                    propertyCount + (option.property() ? 1 : 0),
                    propertyValue + (option.property() ? option.value() : 0));
        }
    }
}
