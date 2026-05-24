package com.monopoly.pattern.strategy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.simulation.DecisionBroker;
import com.monopoly.simulation.SimulationDecisionCandidate;
import com.monopoly.simulation.SimulationDecisionRequest;
import com.monopoly.simulation.SimulationDecisionResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI strategy adapter used by offline simulations: the backend still generates
 * legal candidates and applies the chosen move, while a shared broker labels
 * the decision.
 */
public final class BrokeredAiPlayStrategy implements AiPlayStrategy, AiChoiceAdvisor {

    private static final Gson GSON = new Gson();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final int MAX_MODEL_CANDIDATES =
            Integer.getInteger("monopoly.simulation.maxCandidates", 32);

    private final DecisionBroker broker;
    private final String sessionId;
    private final Duration decisionTimeout;
    private final AiPlayStrategy fallback = new HardAiPlayStrategy();

    public BrokeredAiPlayStrategy(DecisionBroker broker) {
        this(broker, "", Duration.ofSeconds(Long.getLong("monopoly.simulation.decisionTimeoutSeconds", 30L)));
    }

    public BrokeredAiPlayStrategy(DecisionBroker broker, String sessionId) {
        this(broker, sessionId, Duration.ofSeconds(Long.getLong("monopoly.simulation.decisionTimeoutSeconds", 30L)));
    }

    public BrokeredAiPlayStrategy(DecisionBroker broker, String sessionId, Duration decisionTimeout) {
        if (broker == null) {
            throw new IllegalArgumentException("broker must not be null");
        }
        this.broker = broker;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.decisionTimeout = decisionTimeout == null ? Duration.ofSeconds(30) : decisionTimeout;
    }

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, context);
        if (candidates.isEmpty()) {
            return false;
        }
        candidates = prune(candidates);
        Map<String, AiHeuristics.AiPlayCandidate> byId = new LinkedHashMap<>();
        List<SimulationDecisionCandidate> simulationCandidates = new ArrayList<>();
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            byId.put(candidate.id(), candidate);
            simulationCandidates.add(new SimulationDecisionCandidate(
                    candidate.id(),
                    candidate.summary(),
                    GSON.toJsonTree(candidate.request()).getAsJsonObject()));
        }
        JsonObject contextJson = parsePrompt(DeepSeekAiPlayStrategy.buildUserPrompt(bot, context, candidates));
        SimulationDecisionRequest request = request(
                bot,
                context,
                "PLAY_CARD",
                contextJson,
                simulationCandidates);
        try {
            SimulationDecisionResult result = broker.submitAndWait(request, decisionTimeout);
            AiHeuristics.AiPlayCandidate chosen = byId.get(result.getChoiceId());
            if (chosen == null) {
                chosen = DeepSeekAiPlayStrategy.bestLocalCandidate(candidates);
            }
            bridge.submitPlayAction(chosen.request());
            return true;
        } catch (Exception e) {
            return fallback.tryPlayOneCard(bot, context, bridge);
        }
    }

    @Override
    public AiHeuristics.AiResponseDecision chooseResponse(
            AIPlayer bot,
            GameContext context,
            boolean counterRole) {
        AiHeuristics.AiResponseDecision fallbackDecision =
                AiHeuristics.chooseResponse(bot, context, counterRole);
        if (!fallbackDecision.modelWorthAsking() || fallbackDecision.request() == null) {
            return fallbackDecision;
        }
        List<SimulationDecisionCandidate> candidates = List.of(
                new SimulationDecisionCandidate("PASS", "Pass Just Say No response.", new JsonObject()),
                new SimulationDecisionCandidate(
                        "PLAY_JSN",
                        "Play Just Say No.",
                        GSON.toJsonTree(fallbackDecision.request()).getAsJsonObject()));
        JsonObject contextJson = parsePrompt(
                DeepSeekAiPlayStrategy.buildResponsePrompt(bot, context, counterRole, fallbackDecision));
        try {
            SimulationDecisionResult result = broker.submitAndWait(
                    request(bot, context, "JUST_SAY_NO", contextJson, candidates),
                    decisionTimeout);
            if ("PLAY_JSN".equals(result.getChoiceId())) {
                return AiHeuristics.AiResponseDecision.play(
                        fallbackDecision.request(),
                        "Broker chose Just Say No.");
            }
            return AiHeuristics.AiResponseDecision.pass();
        } catch (Exception e) {
            return fallbackDecision;
        }
    }

    @Override
    public PaymentSettlement.PaymentChoice choosePayment(
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        if (bot == null || amountDue <= 0) {
            return fallbackChoice;
        }
        List<Card> payable = payableCards(bot);
        if (payable.isEmpty()) {
            return fallbackChoice;
        }
        List<PaymentSettlement.PaymentChoice> choices =
                paymentChoices(payable, amountDue, fallbackChoice);
        if (choices.isEmpty()) {
            return fallbackChoice;
        }
        Map<String, PaymentSettlement.PaymentChoice> byId = new LinkedHashMap<>();
        List<SimulationDecisionCandidate> candidates = new ArrayList<>();
        int seq = 1;
        for (PaymentSettlement.PaymentChoice choice : choices) {
            String id = "c" + seq++;
            byId.put(id, choice);
            candidates.add(new SimulationDecisionCandidate(
                    id,
                    "Pay " + choice.amountPaid() + "M with " + cardIds(choice.cards()) + ".",
                    cardIdsPayload(choice.cards(), choice.amountPaid())));
        }
        JsonObject contextJson = parsePrompt(
                DeepSeekAiPlayStrategy.buildPaymentPrompt(bot, context, creditor, amountDue, payable, fallbackChoice));
        try {
            SimulationDecisionResult result = broker.submitAndWait(
                    request(bot, context, "PAYMENT", contextJson, candidates),
                    decisionTimeout);
            return byId.getOrDefault(result.getChoiceId(), fallbackChoice);
        } catch (Exception e) {
            return fallbackChoice;
        }
    }

    @Override
    public List<Card> chooseOverflowDiscards(
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards) {
        if (bot == null || bot.getHandCardCount() <= limit) {
            return fallbackCards;
        }
        int need = bot.getHandCardCount() - limit;
        List<List<Card>> choices = discardChoices(bot.getHandCardsView(), need, fallbackCards);
        if (choices.isEmpty()) {
            return fallbackCards;
        }
        Map<String, List<Card>> byId = new LinkedHashMap<>();
        List<SimulationDecisionCandidate> candidates = new ArrayList<>();
        int seq = 1;
        for (List<Card> choice : choices) {
            String id = "c" + seq++;
            byId.put(id, choice);
            candidates.add(new SimulationDecisionCandidate(
                    id,
                    "Discard " + cardIds(choice) + ".",
                    cardIdsPayload(choice, 0)));
        }
        JsonObject contextJson = parsePrompt(DeepSeekAiPlayStrategy.buildDiscardPrompt(
                bot,
                context,
                limit,
                need,
                fallbackCards));
        try {
            SimulationDecisionResult result = broker.submitAndWait(
                    request(bot, context, "OVERFLOW_DISCARD", contextJson, candidates),
                    decisionTimeout);
            return byId.getOrDefault(result.getChoiceId(), fallbackCards);
        } catch (Exception e) {
            return fallbackCards;
        }
    }

    private SimulationDecisionRequest request(
            AIPlayer bot,
            GameContext context,
            String kind,
            JsonObject contextJson,
            List<SimulationDecisionCandidate> candidates) {
        long seq = REQUEST_SEQUENCE.incrementAndGet();
        String actorId = bot == null ? "" : bot.getPlayerId();
        return new SimulationDecisionRequest(
                sessionId + "-" + actorId + "-" + kind + "-" + seq,
                sessionId,
                actorId,
                kind,
                context == null ? 0L : context.getStateSequence(),
                contextJson,
                candidates);
    }

    private static List<AiHeuristics.AiPlayCandidate> prune(List<AiHeuristics.AiPlayCandidate> candidates) {
        int limit = Math.max(8, MAX_MODEL_CANDIDATES);
        if (candidates.size() <= limit) {
            return candidates;
        }
        List<AiHeuristics.AiPlayCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(
                (AiHeuristics.AiPlayCandidate candidate) ->
                        DeepSeekAiPlayStrategy.candidateScore(candidate)).reversed());
        List<AiHeuristics.AiPlayCandidate> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            AiHeuristics.AiPlayCandidate c = sorted.get(i);
            out.add(new AiHeuristics.AiPlayCandidate("c" + (i + 1), c.request(), c.summary()));
        }
        return out;
    }

    private static List<Card> payableCards(Player player) {
        List<Card> out = new ArrayList<>();
        out.addAll(player.getBankCardsView());
        out.addAll(player.getPropertyCardsView());
        out.removeIf(card -> PayableCards.valueOf(card) <= 0);
        return out;
    }

    private static List<PaymentSettlement.PaymentChoice> paymentChoices(
            List<Card> payable,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        Map<String, PaymentSettlement.PaymentChoice> out = new LinkedHashMap<>();
        addPaymentChoice(out, fallbackChoice);
        int total = payable.stream().mapToInt(PayableCards::valueOf).sum();
        enumeratePayment(payable, 0, new ArrayList<>(), 0, amountDue, total, out);
        return new ArrayList<>(out.values());
    }

    private static void enumeratePayment(
            List<Card> payable,
            int index,
            List<Card> chosen,
            int sum,
            int amountDue,
            int totalPayable,
            Map<String, PaymentSettlement.PaymentChoice> out) {
        if (out.size() >= MAX_MODEL_CANDIDATES) {
            return;
        }
        boolean legal = sum >= amountDue || (totalPayable < amountDue && sum == totalPayable);
        if (legal && !chosen.isEmpty()) {
            addPaymentChoice(out, new PaymentSettlement.PaymentChoice(List.copyOf(chosen), sum));
            if (out.size() >= MAX_MODEL_CANDIDATES) {
                return;
            }
        }
        if (index >= payable.size()) {
            return;
        }
        for (int i = index; i < payable.size(); i++) {
            Card card = payable.get(i);
            chosen.add(card);
            enumeratePayment(
                    payable,
                    i + 1,
                    chosen,
                    sum + PayableCards.valueOf(card),
                    amountDue,
                    totalPayable,
                    out);
            chosen.remove(chosen.size() - 1);
            if (out.size() >= MAX_MODEL_CANDIDATES) {
                return;
            }
        }
    }

    private static void addPaymentChoice(
            Map<String, PaymentSettlement.PaymentChoice> out,
            PaymentSettlement.PaymentChoice choice) {
        if (choice == null || choice.cards() == null || choice.cards().isEmpty()) {
            return;
        }
        out.putIfAbsent(cardIds(choice.cards()), choice);
    }

    private static List<List<Card>> discardChoices(
            List<Card> hand,
            int need,
            List<Card> fallbackCards) {
        Map<String, List<Card>> out = new LinkedHashMap<>();
        addDiscardChoice(out, fallbackCards, need);
        enumerateDiscard(hand, need, 0, new ArrayList<>(), out);
        return new ArrayList<>(out.values());
    }

    private static void enumerateDiscard(
            List<Card> hand,
            int need,
            int index,
            List<Card> chosen,
            Map<String, List<Card>> out) {
        if (out.size() >= MAX_MODEL_CANDIDATES) {
            return;
        }
        if (chosen.size() == need) {
            addDiscardChoice(out, List.copyOf(chosen), need);
            return;
        }
        if (index >= hand.size()) {
            return;
        }
        for (int i = index; i < hand.size(); i++) {
            chosen.add(hand.get(i));
            enumerateDiscard(hand, need, i + 1, chosen, out);
            chosen.remove(chosen.size() - 1);
            if (out.size() >= MAX_MODEL_CANDIDATES) {
                return;
            }
        }
    }

    private static void addDiscardChoice(
            Map<String, List<Card>> out,
            List<Card> cards,
            int need) {
        if (cards == null || cards.size() != need) {
            return;
        }
        out.putIfAbsent(cardIds(cards), List.copyOf(cards));
    }

    private static JsonObject cardIdsPayload(List<Card> cards, int amountPaid) {
        JsonObject payload = new JsonObject();
        JsonArray ids = new JsonArray();
        for (Card card : cards) {
            ids.add(card.getId());
        }
        payload.add("cardIds", ids);
        if (amountPaid > 0) {
            payload.addProperty("amountPaidM", amountPaid);
        }
        return payload;
    }

    private static String cardIds(List<? extends Card> cards) {
        List<String> ids = cards.stream()
                .map(Card::getId)
                .sorted()
                .toList();
        return String.join(",", ids);
    }

    private static JsonObject parsePrompt(String prompt) {
        try {
            return JsonParser.parseString(prompt).getAsJsonObject();
        } catch (RuntimeException e) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("rawPrompt", prompt == null ? "" : prompt);
            return fallback;
        }
    }
}
