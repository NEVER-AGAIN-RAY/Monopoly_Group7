package com.monopoly.pattern.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.pattern.strategy.decision.DecisionTraceSink;
import com.monopoly.pattern.strategy.decision.SimulationDecisionCandidate;
import com.monopoly.pattern.strategy.decision.SimulationDecisionRequest;
import com.monopoly.pattern.strategy.decision.SimulationDecisionResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

final class PaymentAdvisor {

    private static final AtomicLong TRACE_SEQUENCE = new AtomicLong();

    private PaymentAdvisor() {
    }

    static PaymentSettlement.PaymentChoice choosePayment(
            DecisionTraceSink traceSink,
            String traceSessionId,
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        if (!SearchConfig.BOARD_AWARE_PAYMENT || bot == null || amountDue <= 0) {
            recordPaymentTrace(traceSink, traceSessionId, bot, context, creditor, amountDue, fallbackChoice, fallbackChoice);
            return fallbackChoice;
        }
        PaymentSettlement.PaymentChoice chosen = chooseBoardAwarePayment(bot, amountDue, fallbackChoice);
        recordPaymentTrace(traceSink, traceSessionId, bot, context, creditor, amountDue, fallbackChoice, chosen);
        return chosen;
    }

    static PaymentSettlement.PaymentChoice chooseBoardAwarePayment(
            AIPlayer bot,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        PaymentSettlement.PaymentChoice best = boardAwarePaymentChoice(bot, amountDue);
        if (best == null) {
            return fallbackChoice;
        }
        return best;
    }

    static PaymentSettlement.PaymentChoice boardAwarePaymentChoice(
            Player debtor,
            int amountDue) {
        List<Card> payable = payableCards(debtor, amountDue);
        if (payable.isEmpty()) {
            return new PaymentSettlement.PaymentChoice(List.of(), 0);
        }
        int total = payable.stream().mapToInt(PayableCards::valueOf).sum();
        if (total < amountDue) {
            return new PaymentSettlement.PaymentChoice(List.copyOf(payable), total);
        }
        return bestBoardAwarePaymentChoice(debtor, payable, amountDue, total);
    }

    static PaymentSettlement.PaymentChoice bestBoardAwarePaymentChoice(
            Player debtor,
            List<Card> payable,
            int amountDue,
            int total) {
        PaymentSettlement.PaymentChoice[] bestByAmount =
                new PaymentSettlement.PaymentChoice[total + 1];
        double[] bestScores = new double[total + 1];
        Arrays.fill(bestScores, Double.POSITIVE_INFINITY);
        bestByAmount[0] = new PaymentSettlement.PaymentChoice(List.of(), 0);
        bestScores[0] = 0d;

        for (Card card : payable) {
            int value = PayableCards.valueOf(card);
            if (value <= 0) {
                continue;
            }
            double cardScore = value + paymentCardDamage(debtor, card);
            for (int amount = total - value; amount >= 0; amount--) {
                PaymentSettlement.PaymentChoice previous = bestByAmount[amount];
                if (previous == null) {
                    continue;
                }
                int nextAmount = amount + value;
                List<Card> nextCards = new ArrayList<>(previous.cards());
                nextCards.add(card);
                PaymentSettlement.PaymentChoice next =
                        new PaymentSettlement.PaymentChoice(List.copyOf(nextCards), nextAmount);
                double nextScore = bestScores[amount] + cardScore;
                if (isBetterPaymentChoice(nextScore, next, bestScores[nextAmount], bestByAmount[nextAmount])) {
                    bestByAmount[nextAmount] = next;
                    bestScores[nextAmount] = nextScore;
                }
            }
        }

        PaymentSettlement.PaymentChoice best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int amount = amountDue; amount <= total; amount++) {
            PaymentSettlement.PaymentChoice choice = bestByAmount[amount];
            if (choice == null || choice.cards().isEmpty()) {
                continue;
            }
            double score = bestScores[amount];
            if (isBetterPaymentChoice(score, choice, bestScore, best)) {
                best = choice;
                bestScore = score;
            }
        }
        return best;
    }

    static boolean isBetterPaymentChoice(
            double candidateScore,
            PaymentSettlement.PaymentChoice candidate,
            double incumbentScore,
            PaymentSettlement.PaymentChoice incumbent) {
        if (candidate == null) {
            return false;
        }
        if (incumbent == null) {
            return true;
        }
        int c = Double.compare(candidateScore, incumbentScore);
        if (c != 0) {
            return c < 0;
        }
        c = Integer.compare(candidate.amountPaid(), incumbent.amountPaid());
        if (c != 0) {
            return c < 0;
        }
        c = Integer.compare(candidate.cards().size(), incumbent.cards().size());
        if (c != 0) {
            return c < 0;
        }
        return paymentChoiceKey(candidate).compareTo(paymentChoiceKey(incumbent)) < 0;
    }

    static List<Card> payableCards(Player debtor, int amountDue) {
        if (debtor == null) {
            return List.of();
        }
        List<Card> bank = new ArrayList<>();
        int bankTotal = 0;
        for (Card card : debtor.getBankCardsView()) {
            int value = PayableCards.valueOf(card);
            if (card != null && value > 0) {
                bank.add(card);
                bankTotal += value;
            }
        }
        List<Card> out = new ArrayList<>(bank);
        if (bankTotal < amountDue) {
            for (PropertyCard property : debtor.getPropertyCardsView()) {
                if (property != null && PayableCards.valueOf(property) > 0) {
                    out.add(property);
                }
            }
        }
        return out;
    }

    static void enumeratePaymentChoices(
            List<Card> payable,
            int index,
            List<Card> chosen,
            int sum,
            int amountDue,
            int totalPayable,
            List<PaymentSettlement.PaymentChoice> out) {
        if (out.size() >= SearchConfig.MAX_PAYMENT_CANDIDATES) {
            return;
        }
        boolean legal = sum >= amountDue || (totalPayable < amountDue && sum == totalPayable);
        if (legal && !chosen.isEmpty()) {
            out.add(new PaymentSettlement.PaymentChoice(List.copyOf(chosen), sum));
            if (out.size() >= SearchConfig.MAX_PAYMENT_CANDIDATES) {
                return;
            }
        }
        if (index >= payable.size()) {
            return;
        }
        for (int i = index; i < payable.size(); i++) {
            Card card = payable.get(i);
            chosen.add(card);
            enumeratePaymentChoices(
                    payable,
                    i + 1,
                    chosen,
                    sum + PayableCards.valueOf(card),
                    amountDue,
                    totalPayable,
                    out);
            chosen.remove(chosen.size() - 1);
            if (out.size() >= SearchConfig.MAX_PAYMENT_CANDIDATES) {
                return;
            }
        }
    }

    static double paymentChoiceScore(
            Player debtor,
            PaymentSettlement.PaymentChoice choice) {
        if (choice == null) {
            return Double.MAX_VALUE;
        }
        return choice.amountPaid() + paymentBoardDamage(debtor, choice.cards());
    }

    static double paymentBoardDamage(Player debtor, List<Card> cards) {
        if (debtor == null || cards == null || cards.isEmpty()) {
            return 0d;
        }
        double damage = 0d;
        for (Card card : cards) {
            damage += paymentCardDamage(debtor, card);
        }
        return damage;
    }

    static double paymentCardDamage(Player debtor, Card card) {
        if (card instanceof PropertyCard property) {
            return propertyPaymentDamage(debtor, property);
        }
        return 0d;
    }

    static double propertyPaymentDamage(Player debtor, PropertyCard property) {
        String color = paymentPropertyColor(property);
        if (color == null || color.isBlank()) {
            return PayableCards.valueOf(property) * 0.1d;
        }
        int need = Math.max(1, PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3));
        int effective = PropertySetCalculator.effectiveCountForColor(debtor.getPropertyCardsView(), color);
        double damage = PayableCards.valueOf(property) * 0.1d;
        if (effective >= need) {
            damage += SearchConfig.PAYMENT_COMPLETE_SET_BREAK_PENALTY;
        } else if (effective == need - 1) {
            damage += SearchConfig.PAYMENT_NEAR_SET_BREAK_PENALTY;
        }
        if (property instanceof PropertyWildCard) {
            damage += 0.5d;
        }
        return damage;
    }

    static String paymentPropertyColor(PropertyCard property) {
        if (property instanceof PropertyWildCard wild) {
            return SearchConfig.trim(wild.getAssignedColorKey()).toUpperCase(Locale.ROOT);
        }
        return SearchConfig.trim(property.getColorGroup()).toUpperCase(Locale.ROOT);
    }

    static String paymentChoiceKey(PaymentSettlement.PaymentChoice choice) {
        if (choice == null || choice.cards() == null) {
            return "";
        }
        return choice.cards().stream()
                .map(card -> SearchConfig.trim(card.getId()))
                .sorted()
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    static void recordPaymentTrace(
            DecisionTraceSink traceSink,
            String traceSessionId,
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice,
            PaymentSettlement.PaymentChoice chosenChoice) {
        if (!shouldRecordAuxiliaryTrace(bot, context, traceSink)) {
            return;
        }
        List<Card> payable = payableCards(bot, amountDue);
        List<PaymentSettlement.PaymentChoice> choices = new ArrayList<>();
        enumeratePaymentChoices(
                payable,
                0,
                new ArrayList<>(),
                0,
                amountDue,
                payable.stream().mapToInt(PayableCards::valueOf).sum(),
                choices);
        addPaymentTraceChoice(choices, fallbackChoice);
        addPaymentTraceChoice(choices, chosenChoice);
        List<SimulationDecisionCandidate> candidates = new ArrayList<>();
        String fallbackKey = paymentChoiceKey(fallbackChoice);
        String chosenKey = paymentChoiceKey(chosenChoice);
        String fallbackId = "";
        String choiceId = "";
        int seq = 1;
        Set<String> seen = new HashSet<>();
        for (PaymentSettlement.PaymentChoice choice : choices) {
            String key = paymentChoiceKey(choice);
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            String id = "c" + seq++;
            if (key.equals(fallbackKey)) {
                fallbackId = id;
            }
            if (key.equals(chosenKey)) {
                choiceId = id;
            }
            candidates.add(new SimulationDecisionCandidate(
                    id,
                    "Pay " + choice.amountPaid() + "M with " + key + ".",
                    cardIdsPayload(choice.cards(), choice.amountPaid())));
        }
        if (choiceId.isBlank()) {
            choiceId = fallbackId.isBlank() ? "c1" : fallbackId;
        }
        JsonObject contextJson = SearchLookaheadAiPlayStrategy.auxiliaryContextWithMemento(
                DeepSeekAiPlayStrategy.buildPaymentPrompt(
                        bot, context, creditor, amountDue, payable, fallbackChoice),
                context);
        contextJson.addProperty("amountDueM", amountDue);
        contextJson.addProperty("creditorPlayerId", creditor == null ? "" : creditor.getPlayerId());
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("fallbackChoiceId", fallbackId);
        metadata.addProperty("boardAwarePayment", SearchConfig.BOARD_AWARE_PAYMENT);
        recordAuxiliaryTrace(traceSink, traceSessionId, bot, context, "PAYMENT", contextJson, candidates, choiceId, metadata);
    }

    static void addPaymentTraceChoice(
            List<PaymentSettlement.PaymentChoice> choices,
            PaymentSettlement.PaymentChoice choice) {
        if (choice != null && choice.cards() != null && !choice.cards().isEmpty()) {
            choices.add(choice);
        }
    }

    static boolean shouldRecordAuxiliaryTrace(AIPlayer bot, GameContext context, DecisionTraceSink traceSink) {
        return bot != null && context != null && traceSink != null && traceSink != DecisionTraceSink.NONE;
    }

    static JsonObject cardIdsPayload(List<? extends Card> cards, int amountPaid) {
        JsonObject payload = new JsonObject();
        JsonArray ids = new JsonArray();
        if (cards != null) {
            for (Card card : cards) {
                ids.add(card.getId());
            }
        }
        payload.add("cardIds", ids);
        if (amountPaid > 0) {
            payload.addProperty("amountPaidM", amountPaid);
        }
        return payload;
    }

    static void recordAuxiliaryTrace(
            DecisionTraceSink traceSink,
            String traceSessionId,
            AIPlayer bot,
            GameContext context,
            String kind,
            JsonObject contextJson,
            List<SimulationDecisionCandidate> candidates,
            String choiceId,
            JsonObject metadata) {
        if (candidates == null || candidates.isEmpty() || choiceId == null || choiceId.isBlank()) {
            return;
        }
        String actorId = bot.getPlayerId();
        long seq = TRACE_SEQUENCE.incrementAndGet();
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                traceSessionId + "-" + actorId + "-" + kind + "-" + seq,
                traceSessionId,
                actorId,
                kind,
                context == null ? 0L : context.getStateSequence(),
                contextJson == null ? new JsonObject() : contextJson,
                candidates);
        try {
            traceSink.record(request, new SimulationDecisionResult(
                    request.getDecisionId(),
                    choiceId,
                    null,
                    metadata == null ? new JsonObject() : metadata));
        } catch (RuntimeException ex) {
            AiBattleLogger.log("Lookahead",
                    "auxiliary trace write skipped after successful choice: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }
}