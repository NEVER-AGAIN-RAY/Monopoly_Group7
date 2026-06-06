package com.monopoly.pattern.strategy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.strategy.decision.DecisionTraceSink;
import com.monopoly.pattern.strategy.decision.SimulationDecisionCandidate;
import com.monopoly.pattern.strategy.decision.SimulationDecisionRequest;
import com.monopoly.pattern.strategy.decision.SimulationDecisionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local student policy: scores backend-legal play candidates with a JSON ranker.
 * Loads pre-trained linear or small-MLP model exports from backend/models/distillation/
 * (the offline training pipeline that produced them is no longer part of this repo).
 */
public class LocalRankerAiPlayStrategy implements AiPlayStrategy, AiChoiceAdvisor {

    private static final Gson GSON = new Gson();
    private static final AtomicLong TRACE_SEQUENCE = new AtomicLong();
    private static final int MAX_MODEL_CANDIDATES =
            Integer.getInteger("monopoly.localRanker.maxCandidates", 32);
    private static final boolean RANK_AUXILIARY_DECISIONS =
            Boolean.parseBoolean(System.getProperty("monopoly.localRanker.rankAuxiliaryDecisions", "false"));
    private static final boolean TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO =
            Boolean.parseBoolean(System.getProperty(
                    "monopoly.localRanker.trace.includeMemento",
                    "false"));

    private final RankerModel model;
    private final String modelPath;
    private final DecisionTraceSink traceSink;
    private final String traceSessionId;
    private final AiPlayStrategy fallback = new HardAiPlayStrategy();

    public LocalRankerAiPlayStrategy(Path modelPath) {
        this(modelPath, DecisionTraceSink.NONE, "");
    }

    public LocalRankerAiPlayStrategy(
            Path modelPath,
            DecisionTraceSink traceSink,
            String traceSessionId) {
        this.model = RankerModel.load(modelPath);
        this.modelPath = modelPath == null ? "" : modelPath.toString();
        this.traceSink = traceSink == null ? DecisionTraceSink.NONE : traceSink;
        this.traceSessionId = traceSessionId == null ? "" : traceSessionId;
    }

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        if (!shouldRankDecisionKind("PLAY_CARD")) {
            return fallback.tryPlayOneCard(bot, context, bridge);
        }
        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, context);
        if (candidates.isEmpty()) {
            return false;
        }
        candidates = prune(candidates);
        JsonObject baseContext = promptContext(bot, context, candidates);
        JsonObject traceContext = withCounterfactualMemento(bridge, baseContext);
        List<SimulationDecisionCandidate> traceCandidates = playTraceCandidates(candidates);
        ScoredPlayCandidate best = null;
        ScoredPlayCandidate hardChoice = hardChoice(bot, context, candidates, baseContext);
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            ScoredPlayCandidate scored = scorePlayCandidate(candidate, context, baseContext);
            if (best == null || scored.score() > best.score()) {
                best = scored;
            }
        }
        if (best == null) {
            return fallback.tryPlayOneCard(bot, context, bridge);
        }
        ScoredPlayCandidate chosen = choosePlayWithHybridMargin(best, hardChoice);
        try {
            bridge.submitPlayAction(chosen.candidate().request());
            recordTrace(
                    traceRequest(bot, context, "PLAY_CARD", traceContext, traceCandidates),
                    chosen.candidate().id(),
                    chosen.score(),
                    hardChoice,
                    best,
                    chosen != best);
            return true;
        } catch (RuntimeException ex) {
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
        if (!shouldRankDecisionKind("JUST_SAY_NO")) {
            return fallbackDecision;
        }
        if (!fallbackDecision.modelWorthAsking() || fallbackDecision.request() == null) {
            return fallbackDecision;
        }
        List<RankedCandidate<PlayActionRequest>> candidates = List.of(
                new RankedCandidate<>("PASS", "Pass Just Say No response.", new JsonObject(), null),
                new RankedCandidate<>(
                        "PLAY_JSN",
                        "Play Just Say No.",
                        GSON.toJsonTree(fallbackDecision.request()).getAsJsonObject(),
                        fallbackDecision.request()));
        JsonObject baseContext = parsePrompt(
                DeepSeekAiPlayStrategy.buildResponsePrompt(bot, context, counterRole, fallbackDecision));
        String fallbackId = fallbackDecision.playWaiver() ? "PLAY_JSN" : "PASS";
        RankedCandidate<PlayActionRequest> chosen =
                choose("JUST_SAY_NO", context, baseContext, candidates, fallbackId);
        if (chosen != null && "PLAY_JSN".equals(chosen.id()) && chosen.value() != null) {
            recordTrace(
                    traceRequest(bot, context, "JUST_SAY_NO", baseContext, traceCandidates(candidates)),
                    chosen.id(),
                    chosen.score());
            return AiHeuristics.AiResponseDecision.play(
                    chosen.value(),
                    "Local ranker chose Just Say No.");
        }
        recordTrace(
                traceRequest(bot, context, "JUST_SAY_NO", baseContext, traceCandidates(candidates)),
                chosen == null ? "PASS" : chosen.id(),
                chosen == null ? 0d : chosen.score());
        return AiHeuristics.AiResponseDecision.pass();
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
        if (!shouldRankDecisionKind("PAYMENT")) {
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
        List<RankedCandidate<PaymentSettlement.PaymentChoice>> candidates = new ArrayList<>();
        int seq = 1;
        String fallbackId = "";
        String fallbackKey = fallbackChoice == null ? "" : cardIds(fallbackChoice.cards());
        for (PaymentSettlement.PaymentChoice choice : choices) {
            String id = "c" + seq++;
            if (fallbackId.isBlank() && cardIds(choice.cards()).equals(fallbackKey)) {
                fallbackId = id;
            }
            candidates.add(new RankedCandidate<>(
                    id,
                    "Pay " + choice.amountPaid() + "M with " + cardIds(choice.cards()) + ".",
                    cardIdsPayload(choice.cards(), choice.amountPaid()),
                    choice));
        }
        JsonObject baseContext = parsePrompt(
                DeepSeekAiPlayStrategy.buildPaymentPrompt(
                        bot, context, creditor, amountDue, payable, fallbackChoice));
        RankedCandidate<PaymentSettlement.PaymentChoice> chosen =
                choose("PAYMENT", context, baseContext, candidates, fallbackId);
        if (chosen != null) {
            recordTrace(
                    traceRequest(bot, context, "PAYMENT", baseContext, traceCandidates(candidates)),
                    chosen.id(),
                    chosen.score());
        }
        return chosen == null || chosen.value() == null ? fallbackChoice : chosen.value();
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
        if (!shouldRankDecisionKind("OVERFLOW_DISCARD")) {
            return fallbackCards;
        }
        int need = bot.getHandCardCount() - limit;
        List<List<Card>> choices = discardChoices(bot.getHandCardsView(), need, fallbackCards);
        if (choices.isEmpty()) {
            return fallbackCards;
        }
        List<RankedCandidate<List<Card>>> candidates = new ArrayList<>();
        int seq = 1;
        String fallbackId = "";
        String fallbackKey = cardIds(fallbackCards);
        for (List<Card> choice : choices) {
            String id = "c" + seq++;
            if (fallbackId.isBlank() && cardIds(choice).equals(fallbackKey)) {
                fallbackId = id;
            }
            candidates.add(new RankedCandidate<>(
                    id,
                    "Discard " + cardIds(choice) + ".",
                    cardIdsPayload(choice, 0),
                    choice));
        }
        JsonObject baseContext = parsePrompt(
                DeepSeekAiPlayStrategy.buildDiscardPrompt(bot, context, limit, need, fallbackCards));
        RankedCandidate<List<Card>> chosen =
                choose("OVERFLOW_DISCARD", context, baseContext, candidates, fallbackId);
        if (chosen != null) {
            recordTrace(
                    traceRequest(bot, context, "OVERFLOW_DISCARD", baseContext, traceCandidates(candidates)),
                    chosen.id(),
                    chosen.score());
        }
        return chosen == null || chosen.value() == null ? fallbackCards : chosen.value();
    }

    private <T> RankedCandidate<T> choose(
            String decisionKind,
            GameContext context,
            JsonObject baseContext,
            List<RankedCandidate<T>> candidates) {
        return choose(decisionKind, context, baseContext, candidates, "");
    }

    private <T> RankedCandidate<T> choose(
            String decisionKind,
            GameContext context,
            JsonObject baseContext,
            List<RankedCandidate<T>> candidates,
            String fallbackId) {
        RankedCandidate<T> best = null;
        RankedCandidate<T> fallbackScored = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (RankedCandidate<T> candidate : candidates) {
            double[] features = FeatureExtractor.featuresFor(
                    decisionKind,
                    context,
                    baseContext,
                    candidate.payload(),
                    candidate.id(),
                    candidate.summary());
            double score = model.score(features);
            RankedCandidate<T> scored = candidate.withScore(score);
            if (fallbackId != null && fallbackId.equals(candidate.id())) {
                fallbackScored = scored;
            }
            if (best == null || score > bestScore) {
                best = scored;
                bestScore = score;
            }
        }
        double requiredMargin = globalHybridMargin();
        if (requiredMargin >= 0d && best != null && fallbackScored != null
                && best.score() - fallbackScored.score() < requiredMargin) {
            return fallbackScored;
        }
        return best;
    }

    private static boolean shouldRankDecisionKind(String decisionKind) {
        String configured = System.getProperty("monopoly.localRanker.rankedDecisionKinds", "").trim();
        if (configured.isBlank()) {
            configured = RANK_AUXILIARY_DECISIONS
                    ? "PLAY_CARD,JUST_SAY_NO,PAYMENT,OVERFLOW_DISCARD"
                    : "PLAY_CARD";
        }
        return parseDecisionKinds(configured).contains(trim(decisionKind).toUpperCase(Locale.ROOT));
    }

    private static Set<String> parseDecisionKinds(String raw) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (raw != null) {
            for (String token : raw.split("[,;\\s]+")) {
                String normalized = trim(token).toUpperCase(Locale.ROOT);
                if (!normalized.isBlank()) {
                    out.add(normalized);
                }
            }
        }
        if (out.isEmpty()) {
            out.add("PLAY_CARD");
        }
        return Set.copyOf(out);
    }

    private static JsonObject withCounterfactualMemento(
            AiGameBridge bridge,
            JsonObject contextJson) {
        JsonObject out = contextJson == null ? new JsonObject() : contextJson.deepCopy();
        if (!TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO || !(bridge instanceof GameController controller)) {
            return out;
        }
        JsonObject counterfactual = new JsonObject();
        counterfactual.addProperty("schema", "monopoly-deal-counterfactual-v1");
        counterfactual.addProperty("capture", "before_choice");
        counterfactual.addProperty("mementoJson", GameSessionMemento.capture(controller).toJson());
        out.add("counterfactual", counterfactual);
        return out;
    }

    private SimulationDecisionRequest traceRequest(
            AIPlayer bot,
            GameContext context,
            String kind,
            JsonObject contextJson,
            List<SimulationDecisionCandidate> candidates) {
        String actorId = bot == null ? "" : bot.getPlayerId();
        long seq = TRACE_SEQUENCE.incrementAndGet();
        return new SimulationDecisionRequest(
                traceSessionId + "-" + actorId + "-" + kind + "-" + seq,
                traceSessionId,
                actorId,
                kind,
                context == null ? 0L : context.getStateSequence(),
                contextJson,
                candidates);
    }

    private void recordTrace(SimulationDecisionRequest request, String choiceId, double score) {
        recordTrace(request, choiceId, score, null, null, false);
    }

    private void recordTrace(
            SimulationDecisionRequest request,
            String choiceId,
            double score,
            ScoredPlayCandidate hardChoice,
            ScoredPlayCandidate modelBest,
            boolean hybridFallbackUsed) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "local_ranker");
        metadata.addProperty("score", score);
        metadata.addProperty("modelPath", modelPath);
        if (hardChoice != null) {
            metadata.addProperty("hardChoiceId", hardChoice.candidate().id());
            metadata.addProperty("hardChoiceScore", hardChoice.score());
            metadata.addProperty("hybridMargin", hybridMarginFor(modelBest));
            metadata.addProperty("hybridFallbackUsed", hybridFallbackUsed);
        }
        if (modelBest != null) {
            metadata.addProperty("modelBestId", modelBest.candidate().id());
            metadata.addProperty("modelBestScore", modelBest.score());
            if (hardChoice != null) {
                metadata.addProperty("hybridActualMargin", modelBest.score() - hardChoice.score());
            }
        }
        traceSink.record(request, new SimulationDecisionResult(
                request.getDecisionId(),
                choiceId,
                null,
                metadata));
    }

    private ScoredPlayCandidate choosePlayWithHybridMargin(
            ScoredPlayCandidate modelBest,
            ScoredPlayCandidate hardChoice) {
        double requiredMargin = hybridMarginFor(modelBest);
        if (requiredMargin < 0d || hardChoice == null) {
            return modelBest;
        }
        double margin = modelBest.score() - hardChoice.score();
        return margin >= requiredMargin ? modelBest : hardChoice;
    }

    static double hybridMarginForTest(PlayActionRequest request, String summary) {
        return hybridMarginFor(new ScoredPlayCandidate(
                new AiHeuristics.AiPlayCandidate("test", request, summary),
                0d));
    }

    static boolean shouldRankDecisionKindForTest(String decisionKind) {
        return shouldRankDecisionKind(decisionKind);
    }

    private static double hybridMarginFor(ScoredPlayCandidate candidate) {
        if (candidate == null) {
            return globalHybridMargin();
        }
        PlayActionRequest request = candidate.candidate().request();
        String actionType = trim(request.getActionType()).toUpperCase(Locale.ROOT);
        if ("DEPLOY".equals(actionType)) {
            return marginProperty("monopoly.localRanker.hybridMargin.deploy", globalHybridMargin());
        }
        if ("DEPOSIT".equals(actionType)) {
            return marginProperty("monopoly.localRanker.hybridMargin.deposit", globalHybridMargin());
        }
        if ("DISCARD".equals(actionType)) {
            return marginProperty("monopoly.localRanker.hybridMargin.discard", globalHybridMargin());
        }
        if ("ACTION".equals(actionType)) {
            double actionMargin = marginProperty(
                    "monopoly.localRanker.hybridMargin.action",
                    globalHybridMargin());
            String summary = trim(candidate.candidate().summary()).toUpperCase(Locale.ROOT);
            if (summary.contains("PASS_GO")) {
                return marginProperty("monopoly.localRanker.hybridMargin.passGo", actionMargin);
            }
            if (summary.contains("DEAL_BREAKER")
                    || summary.contains("STEAL_PROPERTY")
                    || summary.contains("FORCED_DEAL")) {
                return marginProperty("monopoly.localRanker.hybridMargin.swingAction", actionMargin);
            }
            if (summary.contains("RENT")
                    || summary.contains("DEBT_COLLECTOR")
                    || summary.contains("BIRTHDAY")) {
                return marginProperty("monopoly.localRanker.hybridMargin.cashAction", actionMargin);
            }
            return actionMargin;
        }
        return globalHybridMargin();
    }

    private ScoredPlayCandidate hardChoice(
            AIPlayer bot,
            GameContext context,
            List<AiHeuristics.AiPlayCandidate> candidates,
            JsonObject baseContext) {
        RecordingBridge recording = new RecordingBridge();
        try {
            if (!fallback.tryPlayOneCard(bot, context, recording) || recording.request == null) {
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        String key = requestKey(recording.request);
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            if (key.equals(requestKey(candidate.request()))) {
                return scorePlayCandidate(candidate, context, baseContext);
            }
        }
        return null;
    }

    private ScoredPlayCandidate scorePlayCandidate(
            AiHeuristics.AiPlayCandidate candidate,
            GameContext context,
            JsonObject baseContext) {
        JsonObject payload = GSON.toJsonTree(candidate.request()).getAsJsonObject();
        double[] features = FeatureExtractor.featuresFor(
                "PLAY_CARD",
                context,
                baseContext,
                payload,
                candidate.id(),
                candidate.summary());
        return new ScoredPlayCandidate(candidate, model.score(features));
    }

    private static String requestKey(PlayActionRequest req) {
        if (req == null) {
            return "";
        }
        return String.join("|",
                trim(req.getActionType()),
                trim(req.getCardId()),
                trim(req.getTargetPlayerId()),
                trim(req.getTargetColorKey()),
                trim(req.getTargetCardId()),
                trim(req.getActorCardId()),
                trim(req.getTargetZone()));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static double marginProperty(String key, double fallback) {
        String raw = System.getProperty(key, "").trim();
        if (raw.isBlank()) {
            return fallback;
        }
        return Double.parseDouble(raw);
    }

    private static double globalHybridMargin() {
        return marginProperty("monopoly.localRanker.hybridMargin", -1d);
    }

    private static List<SimulationDecisionCandidate> playTraceCandidates(
            List<AiHeuristics.AiPlayCandidate> candidates) {
        List<SimulationDecisionCandidate> out = new ArrayList<>();
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            out.add(new SimulationDecisionCandidate(
                    candidate.id(),
                    candidate.summary(),
                    GSON.toJsonTree(candidate.request()).getAsJsonObject()));
        }
        return out;
    }

    private static List<SimulationDecisionCandidate> traceCandidates(
            List<? extends RankedCandidate<?>> candidates) {
        List<SimulationDecisionCandidate> out = new ArrayList<>();
        for (RankedCandidate<?> candidate : candidates) {
            out.add(new SimulationDecisionCandidate(
                    candidate.id(),
                    candidate.summary(),
                    candidate.payload()));
        }
        return out;
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

    private static JsonObject promptContext(
            AIPlayer bot,
            GameContext context,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        try {
            return JsonParser.parseString(
                    DeepSeekAiPlayStrategy.buildUserPrompt(bot, context, candidates)).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
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

    private static JsonObject cardIdsPayload(List<? extends Card> cards, int amountPaid) {
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

    private record RankedCandidate<T>(
            String id,
            String summary,
            JsonObject payload,
            T value,
            double score) {

        private RankedCandidate(String id, String summary, JsonObject payload, T value) {
            this(id, summary, payload, value, 0d);
        }

        private RankedCandidate<T> withScore(double score) {
            return new RankedCandidate<>(id, summary, payload, value, score);
        }
    }

    private record ScoredPlayCandidate(
            AiHeuristics.AiPlayCandidate candidate,
            double score) {
    }

    private static final class RecordingBridge implements AiGameBridge {
        private PlayActionRequest request;

        @Override
        public void submitPlayAction(PlayActionRequest request) {
            this.request = request;
        }
    }

    public interface RankerModel {
        double score(double[] features);

        static RankerModel load(Path path) {
            if (path == null) {
                throw new IllegalArgumentException("local ranker model path must not be null");
            }
            try {
                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                String schema = string(root, "schema");
                return switch (schema) {
                    case "monopoly-deal-linear-ranker-v1" -> LinearModel.load(root);
                    case "monopoly-deal-mlp-ranker-v1" -> MlpModel.load(root);
                    case "monopoly-deal-ensemble-ranker-v1" -> EnsembleModel.load(root, path.toAbsolutePath().getParent());
                    default -> throw new IllegalArgumentException("Unsupported local ranker schema: " + schema);
                };
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read local ranker model: " + path, e);
            }
        }
    }

    private static final class LinearModel implements RankerModel {
        private final double bias;
        private final double[] weights;
        private final int inputDim;

        private LinearModel(double bias, double[] weights, int inputDim) {
            this.bias = bias;
            this.weights = weights;
            this.inputDim = inputDim;
        }

        private static LinearModel load(JsonObject root) {
            int inputDim = root.get("inputDim").getAsInt();
            JsonArray arr = root.getAsJsonArray("weights");
            if (arr == null || arr.size() != inputDim) {
                throw new IllegalArgumentException("Ranker weight length does not match inputDim");
            }
            double[] weights = new double[inputDim];
            for (int i = 0; i < arr.size(); i++) {
                weights[i] = arr.get(i).getAsDouble();
            }
            return new LinearModel(root.get("bias").getAsDouble(), weights, inputDim);
        }

        @Override
        public double score(double[] features) {
            double[] compatibleFeatures = compatibleFeatures(features, inputDim);
            if (compatibleFeatures.length != inputDim) {
                throw new IllegalArgumentException(
                        "Feature length " + features.length + " does not match model inputDim " + inputDim);
            }
            double sum = bias;
            for (int i = 0; i < weights.length; i++) {
                sum += weights[i] * compatibleFeatures[i];
            }
            return sum;
        }
    }

    private static final class MlpModel implements RankerModel {
        private final int inputDim;
        private final List<DenseLayer> layers;

        private MlpModel(int inputDim, List<DenseLayer> layers) {
            this.inputDim = inputDim;
            this.layers = List.copyOf(layers);
        }

        private static MlpModel load(JsonObject root) {
            int inputDim = root.get("inputDim").getAsInt();
            JsonArray arr = root.getAsJsonArray("layers");
            if (arr == null || arr.isEmpty()) {
                throw new IllegalArgumentException("MLP ranker has no layers");
            }
            List<DenseLayer> layers = new ArrayList<>();
            int expectedInput = inputDim;
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) {
                    throw new IllegalArgumentException("MLP layer must be object at " + i);
                }
                DenseLayer layer = DenseLayer.load(arr.get(i).getAsJsonObject());
                if (layer.inputDim != expectedInput) {
                    throw new IllegalArgumentException(
                            "MLP layer " + i + " inputDim " + layer.inputDim
                                    + " does not match expected " + expectedInput);
                }
                layers.add(layer);
                expectedInput = layer.outputDim;
            }
            return new MlpModel(inputDim, layers);
        }

        @Override
        public double score(double[] features) {
            double[] compatibleFeatures = compatibleFeatures(features, inputDim);
            if (compatibleFeatures.length != inputDim) {
                throw new IllegalArgumentException(
                        "Feature length " + features.length + " does not match model inputDim " + inputDim);
            }
            double[] state = compatibleFeatures;
            for (DenseLayer layer : layers) {
                state = layer.forward(state);
            }
            if (state.length != 1) {
                throw new IllegalArgumentException("MLP ranker output length must be 1, got " + state.length);
            }
            return state[0];
        }
    }

    private static double[] compatibleFeatures(double[] features, int inputDim) {
        if (features == null) {
            return new double[0];
        }
        if (features.length == inputDim) {
            return features;
        }
        if (features.length > inputDim) {
            double[] out = new double[inputDim];
            System.arraycopy(features, 0, out, 0, inputDim);
            return out;
        }
        return features;
    }

    private static final class DenseLayer {
        private final int inputDim;
        private final int outputDim;
        private final String activation;
        private final double[][] weights;
        private final double[] bias;

        private DenseLayer(
                int inputDim,
                int outputDim,
                String activation,
                double[][] weights,
                double[] bias) {
            this.inputDim = inputDim;
            this.outputDim = outputDim;
            this.activation = activation;
            this.weights = weights;
            this.bias = bias;
        }

        private static DenseLayer load(JsonObject root) {
            if (!"dense".equals(string(root, "type"))) {
                throw new IllegalArgumentException("Only dense MLP layers are supported");
            }
            int inputDim = root.get("inputDim").getAsInt();
            int outputDim = root.get("outputDim").getAsInt();
            String activation = string(root, "activation");
            JsonArray weightRows = root.getAsJsonArray("weights");
            JsonArray biasArr = root.getAsJsonArray("bias");
            if (weightRows == null || weightRows.size() != outputDim) {
                throw new IllegalArgumentException("Dense layer weight rows do not match outputDim");
            }
            if (biasArr == null || biasArr.size() != outputDim) {
                throw new IllegalArgumentException("Dense layer bias length does not match outputDim");
            }
            double[][] weights = new double[outputDim][inputDim];
            for (int row = 0; row < outputDim; row++) {
                JsonArray cols = weightRows.get(row).getAsJsonArray();
                if (cols.size() != inputDim) {
                    throw new IllegalArgumentException("Dense layer weight cols do not match inputDim");
                }
                for (int col = 0; col < inputDim; col++) {
                    weights[row][col] = cols.get(col).getAsDouble();
                }
            }
            double[] bias = new double[outputDim];
            for (int i = 0; i < outputDim; i++) {
                bias[i] = biasArr.get(i).getAsDouble();
            }
            return new DenseLayer(inputDim, outputDim, activation, weights, bias);
        }

        private double[] forward(double[] input) {
            if (input.length != inputDim) {
                throw new IllegalArgumentException(
                        "Dense input length " + input.length + " does not match " + inputDim);
            }
            double[] out = new double[outputDim];
            for (int row = 0; row < outputDim; row++) {
                double sum = bias[row];
                for (int col = 0; col < inputDim; col++) {
                    sum += weights[row][col] * input[col];
                }
                out[row] = activate(sum);
            }
            return out;
        }

        private double activate(double value) {
            return switch (activation) {
                case "relu" -> Math.max(0.0, value);
                case "linear", "" -> value;
                default -> throw new IllegalArgumentException("Unsupported MLP activation: " + activation);
            };
        }
    }

    private static final class EnsembleModel implements RankerModel {
        private final List<Member> members;
        private final double weightSum;

        private EnsembleModel(List<Member> members) {
            if (members.isEmpty()) {
                throw new IllegalArgumentException("ensemble ranker must contain at least one member");
            }
            this.members = List.copyOf(members);
            this.weightSum = members.stream().mapToDouble(Member::weight).sum();
            if (this.weightSum == 0d) {
                throw new IllegalArgumentException("ensemble ranker member weights must not sum to zero");
            }
        }

        private static EnsembleModel load(JsonObject root, Path baseDir) {
            JsonArray arr = root.getAsJsonArray("members");
            if (arr == null || arr.isEmpty()) {
                throw new IllegalArgumentException("ensemble ranker has no members");
            }
            List<Member> members = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) {
                    throw new IllegalArgumentException("ensemble member must be object at " + i);
                }
                JsonObject item = arr.get(i).getAsJsonObject();
                String rawPath = string(item, "path");
                if (rawPath.isBlank()) {
                    throw new IllegalArgumentException("ensemble member path is required at " + i);
                }
                Path memberPath = Path.of(rawPath);
                if (!memberPath.isAbsolute() && baseDir != null) {
                    memberPath = baseDir.resolve(memberPath).normalize();
                }
                double weight = item.has("weight") && !item.get("weight").isJsonNull()
                        ? item.get("weight").getAsDouble()
                        : 1d;
                members.add(new Member(RankerModel.load(memberPath), weight));
            }
            return new EnsembleModel(members);
        }

        @Override
        public double score(double[] features) {
            double total = 0d;
            for (Member member : members) {
                total += member.weight() * member.model().score(features);
            }
            return total / weightSum;
        }

        private record Member(RankerModel model, double weight) {
        }
    }

    public static final class FeatureExtractor {
        private static final List<String> DECISION_KINDS = List.of(
                "PLAY_CARD", "JUST_SAY_NO", "PAYMENT", "OVERFLOW_DISCARD");
        private static final List<String> ACTION_TYPES = List.of(
                "ACTION", "DEPLOY", "DEPOSIT", "DISCARD", "RESPONSE_PASS");
        private static final List<String> EFFECT_CODES = List.of(
                "BIRTHDAY",
                "DEAL_BREAKER",
                "DEBT_COLLECTOR",
                "DOUBLE_RENT",
                "FORCED_DEAL",
                "HOTEL",
                "HOUSE",
                "PASS_GO",
                "RENT",
                "RENT_DUAL",
                "RENT_WAIVER",
                "STEAL_PROPERTY");
        private static final List<String> COLORS = List.of(
                "BROWN",
                "LIGHT_BLUE",
                "PINK",
                "ORANGE",
                "RED",
                "YELLOW",
                "GREEN",
                "DARK_BLUE",
                "RAILROAD",
                "UTILITY");
        private static final List<String> CARD_KINDS = List.of("ACTION", "MONEY", "PROPERTY", "WILD");
        private static final List<String> SUMMARY_TOKENS = List.of(
                "BANK",
                "DEAL_BREAKER",
                "DEPLOY",
                "DISCARD",
                "DOUBLE",
                "FORCED",
                "JUST SAY NO",
                "PAY",
                "PASS",
                "PASS_GO",
                "RENT",
                "STEAL");
        private static final List<String> TACTIC_TAGS = List.of(
                "creates-or-completes-set",
                "property-development",
                "deal-breaker-full-set-swing",
                "forced-deal-property-swing",
                "sly-deal-property-acquisition",
                "cash-pressure",
                "card-draw-tempo",
                "passive-bank-cash",
                "attacks-current-leader",
                "blocks-near-win-opponent",
                "same-team-target",
                "hard-opponent-target",
                "self-target-check",
                "self-near-win-tempo",
                "pressures-hard-local-opponent");

        private FeatureExtractor() {
        }

        static double[] featuresFor(
                String decisionKind,
                GameContext context,
                JsonObject baseContext,
                JsonObject payload,
                String candidateId,
                String summary) {
            return featuresFor(
                    decisionKind,
                    context == null ? 0L : context.getStateSequence(),
                    baseContext,
                    payload,
                    candidateId,
                    summary);
        }

        public static double[] featuresFor(
                String decisionKind,
                long stateSequence,
                JsonObject baseContext,
                JsonObject payload,
                String candidateId,
                String summary) {
            List<Double> vec = new ArrayList<>(169);
            JsonObject gameMeta = object(baseContext, "gameMeta");
            JsonObject self = object(baseContext, "self");
            JsonArray players = array(baseContext, "players");
            JsonObject decision = object(baseContext, "decision");
            String s = summary == null ? "" : summary;

            addOneHot(vec, decisionKind, DECISION_KINDS);
            vec.add(norm(number(gameMeta, "playerCount"), 5));
            vec.add(norm(number(gameMeta, "roundNumber"), 30));
            vec.add(norm(number(gameMeta, "actionsUsedThisTurn"), 3));
            vec.add(norm(number(gameMeta, "actionsRemainingThisTurn"), 3));
            vec.add(norm(number(self, "bankM"), 30));
            vec.add(norm(number(self, "propertyPayM"), 40));
            vec.add(norm(number(self, "completeSets"), 3));
            vec.add(norm(number(self, "handCount"), 15));
            vec.add(norm(players.size(), 5));
            vec.add(norm(array(baseContext, "effectStack").size(), 8));
            vec.add(norm(stateSequence, 500));
            vec.add(norm(array(decision, "legalCandidates").size(), 40));
            vec.add(norm(candidateIndex(candidateId), 40));

            double maxOppSets = 0;
            double maxOppBank = 0;
            double maxOppProp = 0;
            for (int i = 0; i < players.size(); i++) {
                if (!players.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject p = players.get(i).getAsJsonObject();
                if (bool(p, "isSelf")) {
                    continue;
                }
                maxOppSets = Math.max(maxOppSets, number(p, "completeSets"));
                maxOppBank = Math.max(maxOppBank, number(p, "bankM"));
                maxOppProp = Math.max(maxOppProp, number(p, "propertyPayM"));
            }
            vec.add(norm(maxOppSets, 3));
            vec.add(norm(maxOppBank, 30));
            vec.add(norm(maxOppProp, 40));

            addOneHot(vec, string(payload, "actionType"), ACTION_TYPES);
            addOneHot(vec, string(payload, "targetColorKey"), COLORS);
            addOneHot(vec, string(payload, "effectCode"), EFFECT_CODES);

            JsonObject card = findCard(baseContext, string(payload, "cardId"));
            addOneHot(vec, string(card, "kind"), CARD_KINDS);
            addOneHot(vec, string(card, "effectCode"), EFFECT_CODES);
            String color = string(card, "color");
            if (color.isBlank()) {
                color = string(card, "colorGroup");
            }
            addOneHot(vec, color, COLORS);
            vec.add(norm(number(card, "valueM"), 10));
            vec.add(norm(number(payload, "amountPaidM"), 20));
            vec.add(norm(array(payload, "cardIds").size(), 10));
            vec.add(has(payload, "targetPlayerId"));
            vec.add(has(payload, "targetCardId"));
            vec.add(has(payload, "actorCardId"));

            String upper = s.toUpperCase(Locale.ROOT);
            for (String token : SUMMARY_TOKENS) {
                vec.add(upper.contains(token) ? 1.0 : 0.0);
            }
            for (String marker : List.of(
                    "completionScore=",
                    "expectedPaid=",
                    "netScore=",
                    "takeValue=",
                    "giveValue=",
                    "Pay ",
                    "BANK ")) {
                vec.add(norm(numberAfter(s, marker), 100));
            }
            vec.add(markerValue(s, "wild=", "true") ? 1.0 : 0.0);
            vec.add(markerValue(s, "wild=", "false") ? 1.0 : 0.0);
            for (double v : summaryHashFeatures(s, 16)) {
                vec.add(v);
            }
            addSourcePolicyFeatures(vec, baseContext, candidateId);
            addTacticsFeatures(vec, baseContext, candidateId);
            double[] out = new double[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                out[i] = vec.get(i);
            }
            return out;
        }

        private static void addTacticsFeatures(
                List<Double> vec,
                JsonObject context,
                String candidateId) {
            JsonObject tactics = candidateTactics(context, candidateId);
            JsonArray tags = array(tactics, "tags");
            vec.add(tactics.size() > 0 ? 1.0 : 0.0);
            vec.add(norm(number(tactics, "localScore"), 15000));
            vec.add(norm(number(tactics, "netScore"), 15000));
            vec.add(norm(number(tactics, "materialGain"), 15000));
            vec.add(norm(number(tactics, "completionGain"), 100));
            vec.add(norm(number(tactics, "opponentCompletionLoss"), 100));
            vec.add(norm(number(tactics, "expectedPaidM"), 20));
            vec.add(norm(number(tactics, "selfCompleteSets"), 3));
            vec.add(norm(number(tactics, "targetCompleteSets"), 3));
            vec.add(bool(tactics, "selfNearWin") ? 1.0 : 0.0);
            vec.add(bool(tactics, "targetIsLeader") ? 1.0 : 0.0);
            vec.add(bool(tactics, "targetNearWin") ? 1.0 : 0.0);
            vec.add(bool(tactics, "targetSameTeam") ? 1.0 : 0.0);
            vec.add(bool(tactics, "targetHardOpponent") ? 1.0 : 0.0);
            vec.add(bool(tactics, "targetHardOrLocalOpponent") ? 1.0 : 0.0);
            vec.add(bool(tactics, "isPassiveCash") ? 1.0 : 0.0);
            for (String tag : TACTIC_TAGS) {
                vec.add(arrayContains(tags, tag) ? 1.0 : 0.0);
            }
        }

        private static JsonObject candidateTactics(JsonObject context, String candidateId) {
            if (isBlank(candidateId)) {
                return new JsonObject();
            }
            JsonObject decision = object(context, "decision");
            JsonArray candidates = array(decision, "legalCandidates");
            for (int i = 0; i < candidates.size(); i++) {
                if (!candidates.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject candidate = candidates.get(i).getAsJsonObject();
                if (candidateId.equals(string(candidate, "id"))) {
                    return object(candidate, "tactics");
                }
            }
            return new JsonObject();
        }

        private static void addSourcePolicyFeatures(
                List<Double> vec,
                JsonObject context,
                String candidateId) {
            JsonObject sourcePolicy = object(context, "sourcePolicy");
            JsonObject metadata = object(sourcePolicy, "metadata");
            String sourceChoiceId = string(sourcePolicy, "choiceId");
            String hardChoiceId = string(metadata, "hardChoiceId");
            String modelBestId = string(metadata, "modelBestId");
            JsonObject scores = object(metadata, "candidateScores");
            JsonObject adjustments = object(metadata, "candidateAdjustments");
            Double ownScore = optionalNumber(scores, candidateId);
            Double sourceScore = optionalNumber(scores, sourceChoiceId);
            Double hardScore = optionalNumber(scores, hardChoiceId);
            Double modelBestScore = optionalNumber(metadata, "modelBestScore");
            if (modelBestScore == null) {
                modelBestScore = optionalNumber(scores, modelBestId);
            }
            if (hardScore == null) {
                hardScore = optionalNumber(metadata, "hardChoiceScore");
            }
            vec.add(sourcePolicy.size() > 0 ? 1.0 : 0.0);
            vec.add(!isBlank(candidateId) && candidateId.equals(sourceChoiceId) ? 1.0 : 0.0);
            vec.add(!isBlank(candidateId) && candidateId.equals(hardChoiceId) ? 1.0 : 0.0);
            vec.add(!isBlank(candidateId) && candidateId.equals(modelBestId) ? 1.0 : 0.0);
            vec.add(normOrZero(ownScore, 15000));
            vec.add(normOrZero(sourceScore, 15000));
            vec.add(normOrZero(hardScore, 15000));
            vec.add(normOrZero(modelBestScore, 15000));
            vec.add(normOrZero(delta(ownScore, sourceScore), 15000));
            vec.add(normOrZero(delta(ownScore, hardScore), 15000));
            vec.add(normOrZero(delta(ownScore, modelBestScore), 15000));
            vec.add(normOrZero(optionalNumber(adjustments, candidateId), 5000));
            vec.add(norm(number(metadata, "hardMargin"), 5000));
            vec.add(bool(metadata, "hardFallbackUsed") ? 1.0 : 0.0);
            vec.add(bool(metadata, "rolloutRemainingTurn") ? 1.0 : 0.0);
        }

        private static JsonObject findCard(JsonObject context, String cardId) {
            if (cardId == null || cardId.isBlank()) {
                return new JsonObject();
            }
            JsonObject self = object(context, "self");
            JsonObject found = findCardInPlayer(self, cardId);
            if (!found.entrySet().isEmpty()) {
                return found;
            }
            JsonArray players = array(context, "players");
            for (int i = 0; i < players.size(); i++) {
                if (!players.get(i).isJsonObject()) {
                    continue;
                }
                found = findCardInPlayer(players.get(i).getAsJsonObject(), cardId);
                if (!found.entrySet().isEmpty()) {
                    return found;
                }
            }
            return new JsonObject();
        }

        private static JsonObject findCardInPlayer(JsonObject player, String cardId) {
            for (String key : List.of("handCards", "bankCards", "propertyZoneCards")) {
                JsonArray arr = array(player, key);
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i).isJsonObject()) {
                        JsonObject card = arr.get(i).getAsJsonObject();
                        if (cardId.equals(string(card, "id"))) {
                            return card;
                        }
                    }
                }
            }
            return new JsonObject();
        }

        private static void addOneHot(List<Double> vec, String value, List<String> vocab) {
            String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
            boolean matched = false;
            for (String token : vocab) {
                boolean hit = normalized.equals(token);
                matched |= hit;
                vec.add(hit ? 1.0 : 0.0);
            }
            vec.add(!normalized.isBlank() && !matched ? 1.0 : 0.0);
        }

        private static double[] summaryHashFeatures(String summary, int bins) {
            double[] values = new double[bins];
            String normalized = summary == null
                    ? ""
                    : summary.toUpperCase(Locale.ROOT).replace(",", " ").replace(".", " ");
            for (String token : normalized.split("\\s+")) {
                if (!token.isBlank()) {
                    values[stableTokenBucket(token, bins)] += 1.0;
                }
            }
            for (int i = 0; i < values.length; i++) {
                values[i] = Math.min(1.0, values[i] / 3.0);
            }
            return values;
        }

        private static int stableTokenBucket(String token, int bins) {
            long h = 2166136261L;
            byte[] bytes = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (byte b : bytes) {
                h ^= (b & 0xff);
                h = (h * 16777619L) & 0xffffffffL;
            }
            return (int) (h % bins);
        }

        private static JsonObject object(JsonObject parent, String key) {
            return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                    ? parent.getAsJsonObject(key)
                    : new JsonObject();
        }

        private static JsonArray array(JsonObject parent, String key) {
            return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                    ? parent.getAsJsonArray(key)
                    : new JsonArray();
        }

        private static String string(JsonObject obj, String key) {
            if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
                return "";
            }
            try {
                return obj.get(key).getAsString();
            } catch (RuntimeException e) {
                return "";
            }
        }

        private static double number(JsonObject obj, String key) {
            if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
                return 0.0;
            }
            try {
                return obj.get(key).getAsDouble();
            } catch (RuntimeException e) {
                return 0.0;
            }
        }

        private static Double optionalNumber(JsonObject obj, String key) {
            if (obj == null || key == null || key.isBlank() || !obj.has(key) || obj.get(key).isJsonNull()) {
                return null;
            }
            try {
                return obj.get(key).getAsDouble();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static double normOrZero(Double value, double scale) {
            return value == null ? 0.0 : norm(value, scale);
        }

        private static Double delta(Double a, Double b) {
            return a == null || b == null ? null : a - b;
        }

        private static double norm(double value, double scale) {
            if (scale <= 0) {
                return value;
            }
            return Math.max(-5.0, Math.min(5.0, value / scale));
        }

        private static double has(JsonObject obj, String key) {
            return obj != null && key != null && obj.has(key) && !obj.get(key).isJsonNull()
                    && !string(obj, key).isBlank()
                    ? 1.0
                    : 0.0;
        }

        private static boolean bool(JsonObject obj, String key) {
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
                return false;
            }
            try {
                return obj.get(key).getAsBoolean();
            } catch (RuntimeException e) {
                return false;
            }
        }

        private static boolean arrayContains(JsonArray values, String expected) {
            if (values == null || expected == null) {
                return false;
            }
            for (int i = 0; i < values.size(); i++) {
                try {
                    if (expected.equals(values.get(i).getAsString())) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // Ignore malformed non-string tag entries from old traces.
                }
            }
            return false;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static double candidateIndex(String candidateId) {
            if (candidateId == null) {
                return 0.0;
            }
            String digits = candidateId.replaceAll("\\D+", "");
            if (digits.isBlank()) {
                return 0.0;
            }
            try {
                return Double.parseDouble(digits);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        private static double numberAfter(String text, String marker) {
            if (text == null || marker == null) {
                return 0.0;
            }
            int start = text.indexOf(marker);
            if (start < 0) {
                return 0.0;
            }
            start += marker.length();
            int end = start;
            while (end < text.length()) {
                char ch = text.charAt(end);
                if (!Character.isDigit(ch) && ch != '.' && ch != '-') {
                    break;
                }
                end++;
            }
            if (end == start) {
                return 0.0;
            }
            try {
                return Double.parseDouble(text.substring(start, end));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        private static boolean markerValue(String text, String marker, String expected) {
            if (text == null || marker == null || expected == null) {
                return false;
            }
            String lowerText = text.toLowerCase(Locale.ROOT);
            String lowerMarker = marker.toLowerCase(Locale.ROOT);
            int start = lowerText.indexOf(lowerMarker);
            if (start < 0) {
                return false;
            }
            start += marker.length();
            int end = start;
            while (end < text.length()) {
                char ch = text.charAt(end);
                if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '-') {
                    break;
                }
                end++;
            }
            return expected.equalsIgnoreCase(text.substring(start, end));
        }
    }

    private static String string(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
