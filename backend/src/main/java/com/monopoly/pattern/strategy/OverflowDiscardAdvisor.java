package com.monopoly.pattern.strategy;

import com.google.gson.JsonObject;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.pattern.strategy.decision.DecisionTraceSink;
import com.monopoly.pattern.strategy.decision.SimulationDecisionCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class OverflowDiscardAdvisor {

    private OverflowDiscardAdvisor() {
    }

    static List<Card> chooseOverflowDiscards(
            DecisionTraceSink traceSink,
            String traceSessionId,
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards) {
        if (!SearchConfig.BOARD_AWARE_OVERFLOW_DISCARD || bot == null || bot.getHandCardCount() <= limit) {
            recordOverflowTrace(traceSink, traceSessionId, bot, context, limit, fallbackCards, fallbackCards);
            return fallbackCards;
        }
        List<Card> hand = new ArrayList<>(bot.getHandCardsView());
        List<Card> chosen = new ArrayList<>();
        int effectiveLimit = Math.max(0, limit);
        while (hand.size() > effectiveLimit) {
            Card discard = hand.stream()
                    .min(Comparator
                            .comparingDouble((Card card) -> overflowDiscardRetentionScore(bot, card))
                            .thenComparing(card -> SearchConfig.trim(card.getId())))
                    .orElse(hand.get(hand.size() - 1));
            hand.remove(discard);
            chosen.add(discard);
        }
        recordOverflowTrace(traceSink, traceSessionId, bot, context, limit, fallbackCards, chosen);
        return chosen;
    }

    static double overflowDiscardRetentionScore(AIPlayer bot, Card card) {
        if (card == null) {
            return -1d;
        }
        if (card instanceof PropertyWildCard wild) {
            return wild.getWildPropertyKind() == PropertyWildCard.WildPropertyKind.ANY_COLOR
                    ? 1_050d
                    : 920d + PayableCards.valueOf(wild) * 12d;
        }
        if (card instanceof PropertyCard property) {
            return propertyOverflowRetentionScore(bot, property);
        }
        if (card instanceof ActionCard action) {
            return actionOverflowRetentionScore(action);
        }
        return PayableCards.valueOf(card) * 10d;
    }

    static double propertyOverflowRetentionScore(AIPlayer bot, PropertyCard property) {
        String color = SearchConfig.trim(property.getColorGroup()).toUpperCase(Locale.ROOT);
        int value = PayableCards.valueOf(property);
        if (bot == null || color.isBlank()) {
            return 520d + value * 20d;
        }
        int need = Math.max(1, PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3));
        int effective = PropertySetCalculator.effectiveCountForColor(bot.getPropertyCardsView(), color);
        int after = effective + 1;
        if (after >= need) {
            return 1_120d + value * 18d;
        }
        if (after == need - 1) {
            return 820d + value * 18d;
        }
        if (after == 1) {
            return 390d + value * 18d;
        }
        return 560d + value * 18d;
    }

    static double actionOverflowRetentionScore(ActionCard action) {
        String effect = SearchConfig.trim(action.getEffectCode()).toUpperCase(Locale.ROOT);
        int value = PayableCards.valueOf(action);
        double base = switch (effect) {
            case "DEAL_BREAKER" -> 1_080d;
            case "RENT_WAIVER" -> 980d;
            case "STEAL_PROPERTY", "FORCED_DEAL" -> 930d;
            case "PASS_GO" -> 760d;
            case "HOUSE", "HOTEL" -> 735d;
            case "DEBT_COLLECTOR", "BIRTHDAY" -> 650d;
            case "DOUBLE_RENT", "RENT", "RENT_DUAL" -> 610d;
            default -> 520d;
        };
        return base + value * 10d;
    }

    static void enumerateDiscardChoices(
            List<Card> hand,
            int need,
            int index,
            List<Card> chosen,
            List<List<Card>> out) {
        if (out.size() >= SearchConfig.MAX_PAYMENT_CANDIDATES || need <= 0) {
            return;
        }
        if (chosen.size() == need) {
            out.add(List.copyOf(chosen));
            return;
        }
        if (hand == null || index >= hand.size()) {
            return;
        }
        for (int i = index; i < hand.size(); i++) {
            chosen.add(hand.get(i));
            enumerateDiscardChoices(hand, need, i + 1, chosen, out);
            chosen.remove(chosen.size() - 1);
            if (out.size() >= SearchConfig.MAX_PAYMENT_CANDIDATES) {
                return;
            }
        }
    }

    static void addDiscardTraceChoice(List<List<Card>> choices, List<Card> cards, int need) {
        if (cards != null && cards.size() == need) {
            choices.add(List.copyOf(cards));
        }
    }

    static String cardIds(List<? extends Card> cards) {
        if (cards == null) {
            return "";
        }
        return cards.stream()
                .map(Card::getId)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    static void recordOverflowTrace(
            DecisionTraceSink traceSink,
            String traceSessionId,
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards,
            List<Card> chosenCards) {
        if (!PaymentAdvisor.shouldRecordAuxiliaryTrace(bot, context, traceSink)) {
            return;
        }
        int need = bot == null ? 0 : Math.max(0, bot.getHandCardCount() - limit);
        List<List<Card>> choices = new ArrayList<>();
        enumerateDiscardChoices(bot == null ? List.of() : bot.getHandCardsView(), need, 0, new ArrayList<>(), choices);
        addDiscardTraceChoice(choices, fallbackCards, need);
        addDiscardTraceChoice(choices, chosenCards, need);
        String fallbackKey = cardIds(fallbackCards);
        String chosenKey = cardIds(chosenCards);
        String fallbackId = "";
        String choiceId = "";
        int seq = 1;
        Set<String> seen = new HashSet<>();
        List<SimulationDecisionCandidate> candidates = new ArrayList<>();
        for (List<Card> choice : choices) {
            String key = cardIds(choice);
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
                    "Discard " + key + ".",
                    PaymentAdvisor.cardIdsPayload(choice, 0)));
        }
        if (choiceId.isBlank()) {
            choiceId = fallbackId.isBlank() ? "c1" : fallbackId;
        }
        JsonObject contextJson = SearchLookaheadAiPlayStrategy.auxiliaryContextWithMemento(
                DeepSeekAiPlayStrategy.buildDiscardPrompt(bot, context, limit, need, fallbackCards),
                context);
        contextJson.addProperty("handLimit", limit);
        contextJson.addProperty("discardCount", need);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("fallbackChoiceId", fallbackId);
        metadata.addProperty("boardAwareOverflowDiscard", SearchConfig.BOARD_AWARE_OVERFLOW_DISCARD);
        PaymentAdvisor.recordAuxiliaryTrace(traceSink, traceSessionId, bot, context, "OVERFLOW_DISCARD", contextJson, candidates, choiceId, metadata);
    }
}