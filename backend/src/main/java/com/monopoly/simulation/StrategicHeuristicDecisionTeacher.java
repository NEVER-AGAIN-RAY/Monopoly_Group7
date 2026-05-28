package com.monopoly.simulation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stronger no-cost teacher for local experiments.
 *
 * <p>This is still a deterministic local policy, not a production oracle. It
 * uses the same backend-legal candidate envelope as DeepSeek, but adds board
 * context to the cheap heuristic so local labels are less tied to candidate
 * order and simple action names.</p>
 */
public final class StrategicHeuristicDecisionTeacher implements SimulationDecisionTeacher {

    private static final String SOURCE = "strategic_heuristic";

    @Override
    public List<SimulationDecisionResult> decideBatch(List<SimulationDecisionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (SimulationDecisionRequest request : requests) {
            Board board = Board.from(request.getContextJson());
            Choice choice = choose(request, board);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("source", SOURCE);
            metadata.addProperty("score", choice.score());
            metadata.addProperty("teacherVersion", "strategic-heuristic-v1");
            metadata.addProperty("selfCompleteSets", board.selfCompleteSets());
            metadata.addProperty("maxOpponentCompleteSets", board.maxOpponentCompleteSets());
            metadata.add("candidateScores", choice.candidateScores());
            out.add(new SimulationDecisionResult(
                    request.getDecisionId(),
                    choice.candidateId(),
                    null,
                    metadata));
        }
        return out;
    }

    private static Choice choose(SimulationDecisionRequest request, Board board) {
        String kind = request.getDecisionKind().trim().toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "JUST_SAY_NO" -> chooseJustSayNo(request, board);
            case "PAYMENT" -> choosePayment(request, board);
            case "OVERFLOW_DISCARD" -> chooseDiscard(request, board);
            default -> choosePlay(request, board);
        };
    }

    private static Choice choosePlay(SimulationDecisionRequest request, Board board) {
        Choice best = null;
        JsonObject scores = new JsonObject();
        int order = 0;
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            double score = playScore(candidate, board) - order * 0.0001d;
            scores.addProperty(candidate.getId(), score);
            Choice choice = new Choice(candidate.getId(), score, scores);
            if (best == null || choice.score() > best.score()) {
                best = choice;
            }
            order++;
        }
        return best != null ? best : first(request);
    }

    private static Choice chooseJustSayNo(SimulationDecisionRequest request, Board board) {
        JsonObject decision = decision(request.getContextJson());
        boolean recommendation = bool(decision, "localRecommendationPlayJustSayNo", false);
        int amountDue = integer(decision, "amountDueM", 0);
        boolean dangerousBoard = board.selfCompleteSets() >= 2 || board.maxOpponentCompleteSets() >= 2;
        boolean play = recommendation || amountDue >= 4 || dangerousBoard && amountDue >= 2;
        String wanted = play ? "PLAY_JSN" : "PASS";
        JsonObject scores = new JsonObject();
        Choice best = null;
        int order = 0;
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            double score;
            if ("PLAY_JSN".equals(candidate.getId())) {
                score = play ? 250d + amountDue * 50d : 30d;
            } else {
                score = play ? 20d : 80d;
            }
            score -= order * 0.0001d;
            scores.addProperty(candidate.getId(), score);
            Choice choice = new Choice(candidate.getId(), score, scores);
            if (wanted.equals(candidate.getId())) {
                return choice;
            }
            if (best == null || choice.score() > best.score()) {
                best = choice;
            }
            order++;
        }
        return best != null ? best : first(request);
    }

    private static Choice choosePayment(SimulationDecisionRequest request, Board board) {
        JsonObject decision = decision(request.getContextJson());
        int amountDue = integer(decision, "amountDueM", 0);
        Map<String, JsonObject> payableCards = cardsById(array(decision, "payableCards"));
        Choice best = null;
        JsonObject scores = new JsonObject();
        int order = 0;
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            JsonObject payload = candidate.getPayload();
            List<String> cardIds = cardIds(payload);
            int paid = integer(payload, "amountPaidM", estimateValue(cardIds, payableCards));
            int bankCards = 0;
            int propertyCards = 0;
            int actionCards = 0;
            int protectedCards = 0;
            int strategicCost = 0;
            for (String cardId : cardIds) {
                JsonObject card = payableCards.get(cardId);
                String zone = string(card, "zone").toUpperCase(Locale.ROOT);
                String kind = string(card, "kind").toUpperCase(Locale.ROOT);
                if ("BANK".equals(zone)) {
                    bankCards++;
                }
                if ("PROPERTY".equals(zone) || "PROPERTY".equals(kind) || "WILD".equals(kind)) {
                    propertyCards++;
                    protectedCards += board.selfCompleteSets() >= 2 ? 1 : 0;
                }
                if ("ACTION".equals(kind)) {
                    actionCards++;
                }
                strategicCost += discardOpportunityCost(card, board);
            }
            boolean covers = paid >= amountDue || amountDue <= 0;
            int overpay = Math.max(0, paid - amountDue);
            double score = (covers ? 140_000d : 20_000d)
                    - overpay * 1_250d
                    - strategicCost
                    - propertyCards * 650d
                    - protectedCards * 1_200d
                    - actionCards * 180d
                    + bankCards * 120d
                    - cardIds.size() * 18d
                    - order * 0.0001d;
            scores.addProperty(candidate.getId(), score);
            Choice choice = new Choice(candidate.getId(), score, scores);
            if (best == null || choice.score() > best.score()) {
                best = choice;
            }
            order++;
        }
        return best != null ? best : first(request);
    }

    private static Choice chooseDiscard(SimulationDecisionRequest request, Board board) {
        JsonObject decision = decision(request.getContextJson());
        Map<String, JsonObject> discardCards = cardsById(array(decision, "legalDiscardCards"));
        Choice best = null;
        JsonObject scores = new JsonObject();
        int order = 0;
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            int cost = 0;
            for (String cardId : cardIds(candidate.getPayload())) {
                cost += discardOpportunityCost(discardCards.get(cardId), board);
            }
            double score = -cost - order * 0.0001d;
            scores.addProperty(candidate.getId(), score);
            Choice choice = new Choice(candidate.getId(), score, scores);
            if (best == null || choice.score() > best.score()) {
                best = choice;
            }
            order++;
        }
        return best != null ? best : first(request);
    }

    private static double playScore(SimulationDecisionCandidate candidate, Board board) {
        String summary = candidate.getSummary() == null ? ""
                : candidate.getSummary().toUpperCase(Locale.ROOT);
        JsonObject payload = candidate.getPayload();
        String actionType = string(payload, "actionType");
        boolean deposit = "DEPOSIT".equalsIgnoreCase(actionType) || summary.contains("BANK ");
        int completion = numberAfter(summary, "COMPLETIONSCORE=");
        int expectedPaid = numberAfter(summary, "EXPECTEDPAID=");
        int netScore = numberAfter(summary, "NETSCORE=");
        double score = 0d;

        if (summary.contains("COMPLETIONSCORE=1000")) {
            score += board.selfCompleteSets() >= 2 ? 60_000d : 18_000d;
        } else if (summary.contains("DEPLOY")) {
            score += 4_500d + completion * (board.selfCompleteSets() >= 2 ? 3.0d : 1.2d);
            if (board.roundNumber() <= 2) {
                score += 700d;
            }
        }

        if (!deposit && summary.contains("DEAL_BREAKER")) {
            score += 11_000d + board.maxOpponentCompleteSets() * 4_000d;
        }
        if (!deposit && summary.contains("FORCED_DEAL")) {
            score += 7_400d + netScore * 1.4d + board.maxOpponentCompleteSets() * 1_200d;
        }
        if (!deposit && summary.contains("STEAL_PROPERTY")) {
            score += 6_900d + board.maxOpponentCompleteSets() * 1_000d;
        }
        if (!deposit && summary.contains("DEBT_COLLECTOR")) {
            score += 5_700d + expectedPaid * 240d;
        }
        if (!deposit && summary.contains("RENT")) {
            score += 5_100d + expectedPaid * (board.playerCount() > 2 ? 220d : 170d);
            if (expectedPaid <= 1 && board.selfBankM() >= 4) {
                score -= 900d;
            }
        }
        if (!deposit && summary.contains("PASS_GO")) {
            score += board.roundNumber() <= 3 ? 5_500d : 4_600d;
        }
        if (!deposit && summary.contains("BIRTHDAY")) {
            score += 4_400d + expectedPaid * (board.playerCount() > 2 ? 260d : 190d);
        }
        if (!deposit && summary.contains("HOUSE")) {
            score += board.selfCompleteSets() > 0 ? 5_200d : 1_000d;
        }
        if (!deposit && summary.contains("HOTEL")) {
            score += board.selfCompleteSets() > 0 ? 5_600d : 1_100d;
        }
        if (deposit) {
            int value = Math.max(numberBefore(summary, "M."), integer(payload, "bankValueM", 0));
            score += 1_000d + value * 140d;
            if (summary.contains("JUST_SAY_NO") || summary.contains("DEAL_BREAKER")) {
                score -= 1_600d;
            }
            if (board.selfBankM() <= 1) {
                score += 900d;
            }
        }
        if (board.maxOpponentCompleteSets() >= 2
                && (summary.contains("DEAL_BREAKER")
                || summary.contains("FORCED_DEAL")
                || summary.contains("STEAL_PROPERTY")
                || summary.contains("RENT"))) {
            score += 2_000d;
        }
        return score;
    }

    private static int discardOpportunityCost(JsonObject card, Board board) {
        if (card == null) {
            return 700;
        }
        String kind = string(card, "kind").toUpperCase(Locale.ROOT);
        String effect = string(card, "effectCode").toUpperCase(Locale.ROOT);
        int value = integer(card, "valueM", 0);
        if ("MONEY".equals(kind)) {
            return value * 110;
        }
        if ("PROPERTY".equals(kind) || "WILD".equals(kind)) {
            return 850 + value * 160 + board.selfCompleteSets() * 180;
        }
        if ("ACTION".equals(kind)) {
            if ("JUST_SAY_NO".equals(effect)) {
                return board.maxOpponentCompleteSets() >= 2 ? 2_200 : 1_600;
            }
            if ("DEAL_BREAKER".equals(effect)) {
                return board.maxOpponentCompleteSets() >= 2 ? 2_100 : 1_450;
            }
            if ("STEAL_PROPERTY".equals(effect) || "FORCED_DEAL".equals(effect)) {
                return 1_150 + board.maxOpponentCompleteSets() * 220;
            }
            if ("RENT".equals(effect) || "RENT_DUAL".equals(effect)) {
                return 820 + board.playerCount() * 45;
            }
            if ("PASS_GO".equals(effect)) {
                return board.roundNumber() <= 3 ? 900 : 700;
            }
            return 540 + value * 70;
        }
        return 450 + value * 90;
    }

    private static Choice first(SimulationDecisionRequest request) {
        SimulationDecisionCandidate candidate = request.getCandidates().get(0);
        JsonObject scores = new JsonObject();
        scores.addProperty(candidate.getId(), 0d);
        return new Choice(candidate.getId(), 0d, scores);
    }

    private static JsonObject decision(JsonObject context) {
        if (context != null && context.has("decision") && context.get("decision").isJsonObject()) {
            return context.getAsJsonObject("decision");
        }
        return new JsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object != null && object.has(key) && object.get(key).isJsonArray()) {
            return object.getAsJsonArray(key);
        }
        return new JsonArray();
    }

    private static Map<String, JsonObject> cardsById(JsonArray cards) {
        Map<String, JsonObject> out = new HashMap<>();
        for (JsonElement element : cards) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject card = element.getAsJsonObject();
            String id = string(card, "id");
            if (!id.isBlank()) {
                out.put(id, card);
            }
        }
        return out;
    }

    private static List<String> cardIds(JsonObject payload) {
        if (payload == null || !payload.has("cardIds") || !payload.get("cardIds").isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : payload.getAsJsonArray("cardIds")) {
            if (element.isJsonPrimitive()) {
                out.add(element.getAsString());
            }
        }
        return out;
    }

    private static int estimateValue(List<String> cardIds, Map<String, JsonObject> cardsById) {
        int sum = 0;
        for (String cardId : cardIds) {
            sum += integer(cardsById.get(cardId), "valueM", 0);
        }
        return sum;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int numberAfter(String s, String marker) {
        int start = s.indexOf(marker);
        if (start < 0) {
            return 0;
        }
        start += marker.length();
        int end = start;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(start, end));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int numberBefore(String s, String marker) {
        int end = s.indexOf(marker);
        if (end < 0) {
            return 0;
        }
        int start = end - 1;
        while (start >= 0 && Character.isDigit(s.charAt(start))) {
            start--;
        }
        if (start == end - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(start + 1, end));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private record Choice(String candidateId, double score, JsonObject candidateScores) {
    }

    private record Board(
            int playerCount,
            int roundNumber,
            int selfBankM,
            int selfCompleteSets,
            int maxOpponentCompleteSets) {

        static Board from(JsonObject context) {
            JsonObject meta = object(context, "gameMeta");
            JsonObject self = object(context, "self");
            JsonArray players = array(context, "players");
            int selfSets = integer(self, "completeSets", 0);
            int maxOpponentSets = 0;
            for (JsonElement element : players) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject player = element.getAsJsonObject();
                if (bool(player, "isSelf", false)) {
                    continue;
                }
                maxOpponentSets = Math.max(maxOpponentSets, integer(player, "completeSets", 0));
            }
            return new Board(
                    integer(meta, "playerCount", Math.max(2, players.size())),
                    integer(meta, "roundNumber", 0),
                    integer(self, "bankM", 0),
                    selfSets,
                    maxOpponentSets);
        }

        private static JsonObject object(JsonObject object, String key) {
            if (object != null && object.has(key) && object.get(key).isJsonObject()) {
                return object.getAsJsonObject(key);
            }
            return new JsonObject();
        }
    }
}
