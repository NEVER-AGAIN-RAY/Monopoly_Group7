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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local student policy: scores backend-legal play candidates with a JSON ranker.
 * Supports both linear and small MLP exports from scripts/distill_dataset.py.
 */
public class LocalRankerAiPlayStrategy implements AiPlayStrategy, AiChoiceAdvisor {

    private static final Gson GSON = new Gson();
    private static final int MAX_MODEL_CANDIDATES =
            Integer.getInteger("monopoly.localRanker.maxCandidates", 32);

    private final RankerModel model;
    private final AiPlayStrategy fallback = new HardAiPlayStrategy();

    public LocalRankerAiPlayStrategy(Path modelPath) {
        this.model = RankerModel.load(modelPath);
    }

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, context);
        if (candidates.isEmpty()) {
            return false;
        }
        candidates = prune(candidates);
        JsonObject baseContext = promptContext(bot, context, candidates);
        AiHeuristics.AiPlayCandidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            JsonObject payload = GSON.toJsonTree(candidate.request()).getAsJsonObject();
            double[] features = FeatureExtractor.featuresFor(
                    "PLAY_CARD",
                    context,
                    baseContext,
                    payload,
                    candidate.id(),
                    candidate.summary());
            double score = model.score(features);
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) {
            return fallback.tryPlayOneCard(bot, context, bridge);
        }
        try {
            bridge.submitPlayAction(best.request());
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
        RankedCandidate<PlayActionRequest> chosen = choose("JUST_SAY_NO", context, baseContext, candidates);
        if (chosen != null && "PLAY_JSN".equals(chosen.id()) && chosen.value() != null) {
            return AiHeuristics.AiResponseDecision.play(
                    chosen.value(),
                    "Local ranker chose Just Say No.");
        }
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
        for (PaymentSettlement.PaymentChoice choice : choices) {
            String id = "c" + seq++;
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
                choose("PAYMENT", context, baseContext, candidates);
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
        int need = bot.getHandCardCount() - limit;
        List<List<Card>> choices = discardChoices(bot.getHandCardsView(), need, fallbackCards);
        if (choices.isEmpty()) {
            return fallbackCards;
        }
        List<RankedCandidate<List<Card>>> candidates = new ArrayList<>();
        int seq = 1;
        for (List<Card> choice : choices) {
            String id = "c" + seq++;
            candidates.add(new RankedCandidate<>(
                    id,
                    "Discard " + cardIds(choice) + ".",
                    cardIdsPayload(choice, 0),
                    choice));
        }
        JsonObject baseContext = parsePrompt(
                DeepSeekAiPlayStrategy.buildDiscardPrompt(bot, context, limit, need, fallbackCards));
        RankedCandidate<List<Card>> chosen =
                choose("OVERFLOW_DISCARD", context, baseContext, candidates);
        return chosen == null || chosen.value() == null ? fallbackCards : chosen.value();
    }

    private <T> RankedCandidate<T> choose(
            String decisionKind,
            GameContext context,
            JsonObject baseContext,
            List<RankedCandidate<T>> candidates) {
        RankedCandidate<T> best = null;
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
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
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
            T value) {
    }

    interface RankerModel {
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
            if (features.length != inputDim) {
                throw new IllegalArgumentException(
                        "Feature length " + features.length + " does not match model inputDim " + inputDim);
            }
            double sum = bias;
            for (int i = 0; i < weights.length; i++) {
                sum += weights[i] * features[i];
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
            if (features.length != inputDim) {
                throw new IllegalArgumentException(
                        "Feature length " + features.length + " does not match model inputDim " + inputDim);
            }
            double[] state = features;
            for (DenseLayer layer : layers) {
                state = layer.forward(state);
            }
            if (state.length != 1) {
                throw new IllegalArgumentException("MLP ranker output length must be 1, got " + state.length);
            }
            return state[0];
        }
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

    static final class FeatureExtractor {
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

        private FeatureExtractor() {
        }

        static double[] featuresFor(
                String decisionKind,
                GameContext context,
                JsonObject baseContext,
                JsonObject payload,
                String candidateId,
                String summary) {
            List<Double> vec = new ArrayList<>(119);
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
            vec.add(norm(context == null ? 0 : context.getStateSequence(), 500));
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
                    "Pay ",
                    "BANK ")) {
                vec.add(norm(numberAfter(s, marker), 100));
            }
            for (double v : summaryHashFeatures(s, 16)) {
                vec.add(v);
            }
            double[] out = new double[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                out[i] = vec.get(i);
            }
            return out;
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
