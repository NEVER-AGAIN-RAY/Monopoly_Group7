package com.monopoly.simulation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zero-cost local teacher used for pipeline smoke tests and cheap pretraining.
 * It scores the same legal candidate envelope that DeepSeek sees, so generated
 * rows still come from real backend states.
 */
public final class HeuristicDecisionTeacher implements SimulationDecisionTeacher {

    private static final String SOURCE = "local_heuristic";

    @Override
    public List<SimulationDecisionResult> decideBatch(List<SimulationDecisionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (SimulationDecisionRequest request : requests) {
            Choice choice = choose(request);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("source", SOURCE);
            metadata.addProperty("score", choice.score());
            out.add(new SimulationDecisionResult(
                    request.getDecisionId(),
                    choice.candidateId(),
                    null,
                    metadata));
        }
        return out;
    }

    private static Choice choose(SimulationDecisionRequest request) {
        String kind = request.getDecisionKind().trim().toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "JUST_SAY_NO" -> chooseJustSayNo(request);
            case "PAYMENT" -> choosePayment(request);
            case "OVERFLOW_DISCARD" -> chooseDiscard(request);
            default -> chooseByPlayScore(request);
        };
    }

    private static Choice chooseByPlayScore(SimulationDecisionRequest request) {
        Choice best = null;
        int order = 0;
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            double score = playScore(candidate) - order * 0.0001d;
            Choice choice = new Choice(candidate.getId(), score);
            if (best == null || choice.score() > best.score()) {
                best = choice;
            }
            order++;
        }
        return best != null ? best : first(request);
    }

    private static Choice chooseJustSayNo(SimulationDecisionRequest request) {
        JsonObject decision = decision(request.getContextJson());
        boolean play = bool(decision, "localRecommendationPlayJustSayNo", false);
        String wanted = play ? "PLAY_JSN" : "PASS";
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            if (wanted.equals(candidate.getId())) {
                return new Choice(candidate.getId(), play ? 100d : 50d);
            }
        }
        return first(request);
    }

    private static Choice choosePayment(SimulationDecisionRequest request) {
        JsonObject decision = decision(request.getContextJson());
        int amountDue = integer(decision, "amountDueM", 0);
        Map<String, JsonObject> payableCards = cardsById(array(decision, "payableCards"));
        Choice best = null;
        int order = 0;
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            JsonObject payload = candidate.getPayload();
            List<String> cardIds = cardIds(payload);
            int paid = integer(payload, "amountPaidM", estimateValue(cardIds, payableCards));
            int propertyCount = 0;
            int unknownCount = 0;
            for (String cardId : cardIds) {
                JsonObject card = payableCards.get(cardId);
                if (card == null) {
                    unknownCount++;
                    continue;
                }
                String zone = string(card, "zone");
                if ("PROPERTY".equalsIgnoreCase(zone)) {
                    propertyCount++;
                }
            }
            boolean covers = paid >= amountDue || amountDue <= 0;
            int overpay = Math.max(0, paid - amountDue);
            double score = (covers ? 100_000d : 10_000d)
                    - overpay * 1_000d
                    - propertyCount * 350d
                    - cardIds.size() * 12d
                    - unknownCount * 50d
                    - order * 0.0001d;
            Choice choice = new Choice(candidate.getId(), score);
            if (best == null || choice.score() > best.score()) {
                best = choice;
            }
            order++;
        }
        return best != null ? best : first(request);
    }

    private static Choice chooseDiscard(SimulationDecisionRequest request) {
        JsonObject decision = decision(request.getContextJson());
        Map<String, JsonObject> discardCards = cardsById(array(decision, "legalDiscardCards"));
        Choice best = null;
        int order = 0;
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            int cost = 0;
            for (String cardId : cardIds(candidate.getPayload())) {
                cost += discardOpportunityCost(discardCards.get(cardId));
            }
            double score = -cost - order * 0.0001d;
            Choice choice = new Choice(candidate.getId(), score);
            if (best == null || choice.score() > best.score()) {
                best = choice;
            }
            order++;
        }
        return best != null ? best : first(request);
    }

    private static double playScore(SimulationDecisionCandidate candidate) {
        String summary = candidate.getSummary() == null ? ""
                : candidate.getSummary().toUpperCase(Locale.ROOT);
        JsonObject payload = candidate.getPayload();
        String actionType = string(payload, "actionType");
        boolean deposit = "DEPOSIT".equalsIgnoreCase(actionType) || summary.contains("BANK ");
        double score = 0d;
        if (summary.contains("COMPLETIONSCORE=1000")) {
            score += 10_000d;
        } else if (summary.contains("DEPLOY")) {
            score += 4_000d + numberAfter(summary, "COMPLETIONSCORE=");
        }
        if (!deposit && summary.contains("DEAL_BREAKER")) {
            score += 9_500d;
        }
        if (!deposit && summary.contains("FORCED_DEAL")) {
            score += 7_000d + numberAfter(summary, "NETSCORE=");
        }
        if (!deposit && summary.contains("STEAL_PROPERTY")) {
            score += 6_500d;
        }
        if (!deposit && summary.contains("DEBT_COLLECTOR")) {
            score += 6_000d + numberAfter(summary, "EXPECTEDPAID=") * 180d;
        }
        if (!deposit && summary.contains("RENT")) {
            score += 5_500d + numberAfter(summary, "EXPECTEDPAID=") * 160d;
        }
        if (!deposit && summary.contains("PASS_GO")) {
            score += 4_800d;
        }
        if (!deposit && summary.contains("BIRTHDAY")) {
            score += 4_000d + numberAfter(summary, "EXPECTEDPAID=") * 220d;
        }
        if (!deposit && (summary.contains("HOUSE") || summary.contains("HOTEL"))) {
            score += 4_200d;
        }
        if (deposit) {
            score += 1_000d + numberBefore(summary, "M.") * 120d;
        }
        return score;
    }

    private static int discardOpportunityCost(JsonObject card) {
        if (card == null) {
            return 600;
        }
        String kind = string(card, "kind").toUpperCase(Locale.ROOT);
        String effect = string(card, "effectCode").toUpperCase(Locale.ROOT);
        int value = integer(card, "valueM", 0);
        if ("MONEY".equals(kind)) {
            return value * 100;
        }
        if ("PROPERTY".equals(kind) || "WILD".equals(kind)) {
            return 700 + value * 120;
        }
        if ("ACTION".equals(kind)) {
            if ("JUST_SAY_NO".equals(effect)) {
                return 1_400;
            }
            if ("DEAL_BREAKER".equals(effect)) {
                return 1_300;
            }
            if ("STEAL_PROPERTY".equals(effect) || "FORCED_DEAL".equals(effect)) {
                return 1_000;
            }
            if ("RENT".equals(effect) || "RENT_DUAL".equals(effect)) {
                return 780;
            }
            if ("PASS_GO".equals(effect)) {
                return 680;
            }
            return 500 + value * 60;
        }
        return 400 + value * 80;
    }

    private static Choice first(SimulationDecisionRequest request) {
        SimulationDecisionCandidate candidate = request.getCandidates().get(0);
        return new Choice(candidate.getId(), 0d);
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

    private record Choice(String candidateId, double score) {
    }
}
