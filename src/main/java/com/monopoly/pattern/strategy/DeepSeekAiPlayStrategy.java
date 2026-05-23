package com.monopoly.pattern.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.dto.PlayActionRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek-backed AI: the model chooses one validated candidate request.
 */
public class DeepSeekAiPlayStrategy implements AiPlayStrategy, AiChoiceAdvisor {

    private static final int MAX_MODEL_CANDIDATES =
            Integer.getInteger("monopoly.deepseek.maxCandidates", 28);
    private static final Pattern CANDIDATE_ID_PATTERN =
            Pattern.compile("\"candidateId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern IDS_PATTERN =
            Pattern.compile("\"cardIds\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT = """
            You are a strong Monopoly Deal AI controller.
            Choose exactly one candidate id from the provided legal candidates.
            Return only compact JSON: {"candidateId":"c1"}.
            Do not include reason, confidence, markdown, or extra text.
            Do not invent candidates or card ids.
            """;
    private static final String RESPONSE_SYSTEM_PROMPT = """
            You are a strong Monopoly Deal AI controller.
            Decide whether to play a Just Say No response card.
            Return only compact JSON: {"playJustSayNo":true}.
            Do not include markdown or extra text.
            """;
    private static final String CARD_IDS_SYSTEM_PROMPT = """
            You are a strong Monopoly Deal AI controller.
            Choose card ids for the requested non-play decision.
            Return only compact JSON: {"cardIds":["id1","id2"]}.
            Do not include markdown or extra text.
            Do not invent card ids.
            """;
    private static final String STRATEGY_CONTEXT = """
            Strategy notes distilled from official rules and common Monopoly Deal strategy guides:
            - Win condition dominates: complete 3 property sets immediately when possible.
            - Use Deal Breaker, Sly Deal, Forced Deal, and high rent to stop opponents near 3 sets.
            - Keep enough bank value to absorb rent/debt; paying from bank usually protects board tempo.
            - Avoid sacrificing deployed properties unless the bill is large or no bank can cover it.
            - Do not waste Just Say No on tiny charges such as 1M unless it prevents a loss, protects a full set,
              or stops a decisive steal/Deal Breaker.
            - Prefer plays that create or defend complete sets over low-impact banking.
            - No change is returned when paying, so avoid large overpayment when smaller legal payments exist.
            - Wild properties are valuable because they complete sets; once assigned here their color is locked.
            """;

    private final DeepSeekClient client = new DeepSeekClient();
    private final AiPlayStrategy fallback = new HardAiPlayStrategy();

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, context);
        if (candidates.isEmpty()) {
            return false;
        }
        candidates = pruneCandidates(candidates);
        if (!DeepSeekClient.enabled()) {
            return fallback.tryPlayOneCard(bot, context, bridge);
        }
        String candidateId = chooseCandidate(bot, context, candidates);
        AiHeuristics.AiPlayCandidate chosen = findCandidate(candidates, candidateId);
        if (chosen == null) {
            chosen = bestLocalCandidate(candidates);
            AiBattleLogger.log("DeepSeek",
                    bot.getPlayerId() + " invalid model candidate; fallback best candidate=" + chosen.id());
        }
        try {
            bridge.submitPlayAction(chosen.request());
            AiBattleLogger.log("Decision",
                    bot.getPlayerId() + " played " + chosen.id() + " " + chosen.summary());
            return true;
        } catch (RuntimeException ex) {
            AiBattleLogger.log("Fallback",
                    bot.getPlayerId() + " DeepSeek candidate rejected: " + ex.getMessage());
            return fallback.tryPlayOneCard(bot, context, bridge);
        }
    }

    public AiHeuristics.AiResponseDecision chooseResponse(
            AIPlayer bot,
            GameContext context,
            boolean counterRole) {
        AiHeuristics.AiResponseDecision fallbackDecision =
                AiHeuristics.chooseResponse(bot, context, counterRole);
        if (!DeepSeekClient.enabled()) {
            return fallbackDecision;
        }
        if (!fallbackDecision.modelWorthAsking()) {
            AiBattleLogger.log("DeepSeek",
                    bot.getPlayerId() + " skipped response model: " + fallbackDecision.reason());
            return fallbackDecision;
        }
        try {
            String prompt = buildResponsePrompt(bot, context, counterRole, fallbackDecision);
            boolean preferredAttempted = client.willUsePreferredForStrictJson();
            String raw = client.complete(RESPONSE_SYSTEM_PROMPT, prompt, true);
            JsonObject json = parseDecisionJson(raw);
            if (preferredAttempted) {
                client.recordPreferredJsonSuccess();
            }
            boolean play = json.has("playJustSayNo") && json.get("playJustSayNo").getAsBoolean();
            if (!play || fallbackDecision.request() == null) {
                return AiHeuristics.AiResponseDecision.pass();
            }
            return fallbackDecision;
        } catch (RuntimeException | java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            AiBattleLogger.log("DeepSeek",
                    bot.getPlayerId() + " response decision fallback: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
            return fallbackDecision;
        }
    }

    @Override
    public PaymentSettlement.PaymentChoice choosePayment(
            AIPlayer bot,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        if (!DeepSeekClient.enabled() || bot == null || amountDue <= 0) {
            return fallbackChoice;
        }
        List<Card> payable = payableCards(bot);
        if (payable.isEmpty()) {
            return fallbackChoice;
        }
        try {
            String prompt = buildPaymentPrompt(bot, amountDue, payable, fallbackChoice);
            List<String> ids = requestCardIds(prompt, payableIds(payable), "payment");
            if (ids == null || ids.isEmpty()) {
                return fallbackChoice;
            }
            int sum = 0;
            List<Card> chosen = new ArrayList<>();
            for (String id : ids) {
                Card card = findCardById(payable, id);
                if (card == null || chosen.contains(card)) {
                    return fallbackChoice;
                }
                chosen.add(card);
                sum += PayableCards.valueOf(card);
            }
            int totalPayable = payable.stream().mapToInt(PayableCards::valueOf).sum();
            if (sum < amountDue && sum < totalPayable) {
                return fallbackChoice;
            }
            return new PaymentSettlement.PaymentChoice(List.copyOf(chosen), sum);
        } catch (RuntimeException | java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            AiBattleLogger.log("DeepSeek",
                    bot.getPlayerId() + " payment decision fallback: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
            return fallbackChoice;
        }
    }

    @Override
    public List<Card> chooseOverflowDiscards(AIPlayer bot, int limit, List<Card> fallbackCards) {
        if (!DeepSeekClient.enabled() || bot == null || bot.getHandCardCount() <= limit) {
            return fallbackCards;
        }
        int need = bot.getHandCardCount() - limit;
        try {
            String prompt = buildDiscardPrompt(bot, limit, need, fallbackCards);
            List<String> ids = requestCardIds(prompt, handIds(bot), "discard");
            if (ids == null || ids.size() != need) {
                return fallbackCards;
            }
            List<Card> chosen = new ArrayList<>();
            for (String id : ids) {
                Card card = findCardById(bot.getHandCardsView(), id);
                if (card == null || chosen.contains(card)) {
                    return fallbackCards;
                }
                chosen.add(card);
            }
            return List.copyOf(chosen);
        } catch (RuntimeException | java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            AiBattleLogger.log("DeepSeek",
                    bot.getPlayerId() + " discard decision fallback: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
            return fallbackCards;
        }
    }

    private String chooseCandidate(
            AIPlayer bot,
            GameContext context,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        String prompt = buildUserPrompt(bot, context, candidates);
        try {
            String id = requestCandidateId(prompt, candidates);
            if (findCandidate(candidates, id) != null) {
                return id;
            }
            String retryPrompt = prompt + "\n\nYour previous candidateId was invalid. "
                    + "Return only JSON with candidateId equal to one of the listed ids.";
            return requestCandidateId(retryPrompt, candidates);
        } catch (RuntimeException | java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            AiBattleLogger.log("DeepSeek",
                    bot.getPlayerId() + " request/parse failed: " + ex.getClass().getSimpleName()
                            + " " + ex.getMessage());
            return null;
        }
    }

    private String requestCandidateId(
            String prompt,
            List<AiHeuristics.AiPlayCandidate> candidates)
            throws java.io.IOException, InterruptedException {
        boolean preferredAttempted = client.willUsePreferredForStrictJson();
        try {
            String raw = client.complete(SYSTEM_PROMPT, prompt, true);
            return candidateIdFromRaw(raw, candidates, preferredAttempted);
        } catch (java.io.IOException transport) {
            if (preferredAttempted) {
                client.recordPreferredJsonFailure(transport.getClass().getSimpleName()
                        + " " + transport.getMessage());
            }
            AiBattleLogger.log("DeepSeek",
                    "decision request failed, retrying fallback: "
                            + transport.getClass().getSimpleName() + " " + transport.getMessage());
            String retryPrompt = prompt + "\n\nReturn valid JSON only. No markdown. "
                    + "The candidateId must be one of: " + candidateIds(candidates) + ".";
            String raw = client.completeFallback(SYSTEM_PROMPT, retryPrompt, true);
            return candidateIdFromRaw(raw, candidates, false);
        } catch (RuntimeException malformed) {
            AiBattleLogger.log("DeepSeek",
                    "malformed decision, retrying strict JSON: "
                            + malformed.getClass().getSimpleName() + " " + malformed.getMessage());
            String retryPrompt = prompt + "\n\nReturn valid JSON only. No markdown. "
                    + "The candidateId must be one of: " + candidateIds(candidates) + ".";
            String raw = client.completeFallback(SYSTEM_PROMPT, retryPrompt, true);
            return candidateIdFromRaw(raw, candidates, false);
        }
    }

    private List<String> requestCardIds(String prompt, List<String> legalIds, String task)
            throws java.io.IOException, InterruptedException {
        boolean preferredAttempted = client.willUsePreferredForStrictJson();
        try {
            String raw = client.complete(CARD_IDS_SYSTEM_PROMPT, prompt, true);
            List<String> ids = cardIdsFromRaw(raw, legalIds);
            if (preferredAttempted) {
                client.recordPreferredJsonSuccess();
            }
            return ids;
        } catch (java.io.IOException transport) {
            if (preferredAttempted) {
                client.recordPreferredJsonFailure(transport.getClass().getSimpleName()
                        + " " + transport.getMessage());
            }
            AiBattleLogger.log("DeepSeek",
                    task + " ids request failed, retrying fallback: "
                            + transport.getClass().getSimpleName() + " " + transport.getMessage());
            String retryPrompt = prompt + "\n\nReturn valid JSON only. Legal cardIds: " + legalIds + ".";
            String raw = client.completeFallback(CARD_IDS_SYSTEM_PROMPT, retryPrompt, true);
            return cardIdsFromRaw(raw, legalIds);
        } catch (RuntimeException malformed) {
            if (preferredAttempted) {
                client.recordPreferredJsonFailure(malformed.getClass().getSimpleName()
                        + " " + malformed.getMessage());
            }
            AiBattleLogger.log("DeepSeek",
                    "malformed " + task + " ids, retrying strict JSON: "
                            + malformed.getClass().getSimpleName() + " " + malformed.getMessage());
            String retryPrompt = prompt + "\n\nReturn valid JSON only. Legal cardIds: " + legalIds + ".";
            String raw = client.completeFallback(CARD_IDS_SYSTEM_PROMPT, retryPrompt, true);
            return cardIdsFromRaw(raw, legalIds);
        }
    }

    private String candidateIdFromRaw(
            String raw,
            List<AiHeuristics.AiPlayCandidate> candidates,
            boolean preferredAttempted) {
        try {
            JsonObject json = parseDecisionJson(raw);
            if (preferredAttempted) {
                client.recordPreferredJsonSuccess();
            }
            return string(json, "candidateId");
        } catch (RuntimeException malformed) {
            if (preferredAttempted) {
                client.recordPreferredJsonFailure(malformed.getClass().getSimpleName()
                        + " " + malformed.getMessage());
            }
            String salvaged = candidateIdFromMalformedDecision(raw, candidates);
            if (salvaged != null) {
                AiBattleLogger.log("DeepSeek",
                        "salvaged candidateId=" + salvaged + " from malformed decision without retry");
                return salvaged;
            }
            throw malformed;
        }
    }

    static String candidateIdFromMalformedDecision(
            String raw,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = CANDIDATE_ID_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        String id = matcher.group(1);
        return findCandidate(candidates, id) == null ? null : id;
    }

    private static List<String> cardIdsFromRaw(String raw, List<String> legalIds) {
        try {
            JsonObject json = parseDecisionJson(raw);
            JsonArray arr = json.has("cardIds") ? json.getAsJsonArray("cardIds") : new JsonArray();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                String id = arr.get(i).getAsString();
                if (!legalIds.contains(id)) {
                    return List.of();
                }
                ids.add(id);
            }
            return ids;
        } catch (RuntimeException malformed) {
            return cardIdsFromMalformedDecision(raw, legalIds);
        }
    }

    static List<String> cardIdsFromMalformedDecision(String raw, List<String> legalIds) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Matcher matcher = IDS_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(matcher.group(1));
        while (quoted.find()) {
            String id = quoted.group(1);
            if (!legalIds.contains(id)) {
                return List.of();
            }
            ids.add(id);
        }
        return ids;
    }

    private static JsonObject parseDecisionJson(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        try {
            return JsonParser.parseString(trimmed).getAsJsonObject();
        } catch (RuntimeException first) {
            String extracted = extractFirstJsonObject(trimmed);
            if (extracted == null) {
                throw first;
            }
            return JsonParser.parseString(extracted).getAsJsonObject();
        }
    }

    private static String extractFirstJsonObject(String s) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
                continue;
            }
            if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static AiHeuristics.AiPlayCandidate findCandidate(
            List<AiHeuristics.AiPlayCandidate> candidates,
            String id) {
        if (id == null) {
            return null;
        }
        for (AiHeuristics.AiPlayCandidate c : candidates) {
            if (id.equals(c.id())) {
                return c;
            }
        }
        return null;
    }

    static AiHeuristics.AiPlayCandidate bestLocalCandidate(
            List<AiHeuristics.AiPlayCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        AiHeuristics.AiPlayCandidate best = candidates.get(0);
        int bestScore = candidateScore(best);
        for (int i = 1; i < candidates.size(); i++) {
            AiHeuristics.AiPlayCandidate candidate = candidates.get(i);
            int score = candidateScore(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static String candidateIds(List<AiHeuristics.AiPlayCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (AiHeuristics.AiPlayCandidate c : candidates) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(c.id());
        }
        return sb.toString();
    }

    private static String buildUserPrompt(
            AIPlayer bot,
            GameContext context,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        JsonObject root = new JsonObject();
        root.addProperty("modelRequested", DeepSeekClient.model());
        root.add("self", playerJson(bot, true));
        JsonArray players = new JsonArray();
        for (Player p : context.getPlayers()) {
            if (p != bot) {
                players.add(playerJson(p, false));
            }
        }
        root.add("opponents", players);
        root.addProperty("turnPlayerId", bot.getPlayerId());
        root.addProperty("rule", "Win immediately at 3 complete property sets. Max 3 plays per turn.");
        root.addProperty("strategy", STRATEGY_CONTEXT);
        JsonArray cands = new JsonArray();
        for (AiHeuristics.AiPlayCandidate c : candidates) {
            JsonObject row = new JsonObject();
            row.addProperty("id", c.id());
            row.addProperty("summary", compactSummary(c.summary()));
            cands.add(row);
        }
        root.add("candidates", cands);
        return root.toString();
    }

    private static String buildResponsePrompt(
            AIPlayer bot,
            GameContext context,
            boolean counterRole,
            AiHeuristics.AiResponseDecision fallbackDecision) {
        JsonObject root = new JsonObject();
        root.addProperty("task", "Decide whether to play Just Say No in Monopoly Deal.");
        root.addProperty("output", "{\"playJustSayNo\":true,\"reason\":\"short\"}");
        root.addProperty("counterRole", counterRole);
        root.add("self", playerJson(bot, true));
        root.addProperty("strategy", STRATEGY_CONTEXT);
        root.addProperty("localRecommendationPlayJustSayNo", fallbackDecision.playWaiver());
        root.addProperty("localRecommendationReason", fallbackDecision.reason());
        JsonArray stack = new JsonArray();
        for (com.monopoly.model.effects.EffectStackEntry entry : context.getEffectStackView()) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", entry.getKind().name());
            row.addProperty("actorPlayerId", entry.getActorPlayerId());
            row.addProperty("tenantPlayerId", entry.getTenantPlayerId());
            row.addProperty("colorKey", entry.getColorKey());
            row.addProperty("amountDue", entry.getAmountDue());
            stack.add(row);
        }
        root.add("effectStack", stack);
        return root.toString();
    }

    private static String buildPaymentPrompt(
            AIPlayer bot,
            int amountDue,
            List<Card> payable,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        JsonObject root = new JsonObject();
        root.addProperty("task", "Choose bank/property cards to pay a Monopoly Deal charge.");
        root.addProperty("amountDueM", amountDue);
        root.addProperty("rule", "Pay from bank/properties only. No change is returned. If unable to cover, pay all payable assets.");
        root.addProperty("strategy", STRATEGY_CONTEXT);
        root.add("self", playerJson(bot, true));
        root.add("payableCards", cardListJson(payable, true));
        JsonArray fallback = new JsonArray();
        if (fallbackChoice != null) {
            for (Card card : fallbackChoice.cards()) {
                fallback.add(card.getId());
            }
        }
        root.add("localFallbackCardIds", fallback);
        root.addProperty("output", "{\"cardIds\":[\"card-id\"]}");
        return root.toString();
    }

    private static String buildDiscardPrompt(
            AIPlayer bot,
            int limit,
            int need,
            List<Card> fallbackCards) {
        JsonObject root = new JsonObject();
        root.addProperty("task", "Choose hand cards to discard down to Monopoly Deal hand limit.");
        root.addProperty("handLimit", limit);
        root.addProperty("discardCount", need);
        root.addProperty("strategy", STRATEGY_CONTEXT);
        root.add("self", playerJson(bot, true));
        root.add("handCards", cardListJson(bot.getHandCardsView(), false));
        JsonArray fallback = new JsonArray();
        if (fallbackCards != null) {
            for (Card card : fallbackCards) {
                fallback.add(card.getId());
            }
        }
        root.add("localFallbackCardIds", fallback);
        root.addProperty("output", "{\"cardIds\":[\"card-id\"]}");
        return root.toString();
    }

    private static JsonObject playerJson(Player p, boolean includeHand) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.getPlayerId());
        o.addProperty("name", p.getDisplayName());
        o.addProperty("bankM", p.totalBankValueM());
        o.addProperty("propertyPayM", p.totalPropertyPaymentValueM());
        o.addProperty("completeSets", p.countCompletePropertySets());
        o.addProperty("handCount", p.getHandCardCount());
        o.add("sets", propertyProgressJson(p));
        if (includeHand) {
            o.add("handSummary", handSummaryJson(p.getHandCardsView()));
        }
        return o;
    }

    private static JsonArray propertyProgressJson(Player p) {
        JsonArray arr = new JsonArray();
        for (var entry : PropertySetCalculator.REQUIRED_BY_COLOR.entrySet()) {
            String color = entry.getKey();
            int count = PropertySetCalculator.effectiveCountForColor(p.getPropertyCardsView(), color);
            if (count <= 0) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("color", color);
            o.addProperty("count", count);
            o.addProperty("need", entry.getValue());
            o.addProperty("complete", count >= entry.getValue());
            arr.add(o);
        }
        return arr;
    }

    private static JsonObject handSummaryJson(List<? extends Card> cards) {
        JsonObject counts = new JsonObject();
        for (Card c : cards) {
            String key = kind(c);
            if (c instanceof ActionCard a) {
                key = a.getEffectCode() == null ? "ACTION" : a.getEffectCode().toUpperCase(Locale.ROOT);
            }
            counts.addProperty(key, counts.has(key) ? counts.get(key).getAsInt() + 1 : 1);
        }
        return counts;
    }

    private static JsonArray cardListJson(List<? extends Card> cards, boolean includeZone) {
        JsonArray arr = new JsonArray();
        if (cards == null) {
            return arr;
        }
        for (Card card : cards) {
            JsonObject row = new JsonObject();
            row.addProperty("id", card.getId());
            row.addProperty("kind", kind(card));
            row.addProperty("name", card.getName());
            row.addProperty("valueM", PayableCards.valueOf(card));
            if (card instanceof ActionCard ac) {
                row.addProperty("effectCode", ac.getEffectCode());
            }
            if (card instanceof PropertyCard pc) {
                row.addProperty("color", normalizeColor(pc));
                if (includeZone) {
                    row.addProperty("zone", "PROPERTY");
                }
            } else if (includeZone) {
                row.addProperty("zone", "BANK");
            }
            arr.add(row);
        }
        return arr;
    }

    private static List<Card> payableCards(Player p) {
        List<Card> cards = new ArrayList<>();
        if (p == null) {
            return cards;
        }
        cards.addAll(p.getBankCardsView());
        cards.addAll(p.getPropertyCardsView());
        return cards;
    }

    private static List<String> payableIds(List<Card> cards) {
        return cards.stream().map(Card::getId).toList();
    }

    private static List<String> handIds(Player p) {
        return p == null ? List.of() : p.getHandCardsView().stream().map(Card::getId).toList();
    }

    private static Card findCardById(List<? extends Card> cards, String id) {
        if (cards == null || id == null) {
            return null;
        }
        for (Card card : cards) {
            if (id.equals(card.getId())) {
                return card;
            }
        }
        return null;
    }

    private static String normalizeColor(PropertyCard card) {
        if (card instanceof PropertyWildCard wild && wild.getAssignedColorKey() != null) {
            return wild.getAssignedColorKey().trim().toUpperCase(Locale.ROOT);
        }
        String color = card.getColorGroup();
        return color == null ? "" : color.trim().toUpperCase(Locale.ROOT);
    }

    private static String compactSummary(String summary) {
        if (summary == null) {
            return "";
        }
        String s = summary;
        s = s.replace("Action ", "");
        s = s.replace("Deploy property ", "DEPLOY ");
        s = s.replace("Deploy wild property as ", "DEPLOY_WILD ");
        s = s.replace("Deposit money/bankable card for ", "BANK ");
        s = s.replace("Deposit action card ", "BANK_ACTION ");
        return s.length() <= 120 ? s : s.substring(0, 120);
    }

    private static String string(JsonObject o, String key) {
        return o != null && o.has(key) ? o.get(key).getAsString() : null;
    }

    private static String kind(Card c) {
        String n = c.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        if (n.contains("MONEY")) {
            return "MONEY";
        }
        if (n.contains("WILD")) {
            return "WILD";
        }
        if (n.contains("PROPERTY")) {
            return "PROPERTY";
        }
        if (n.contains("ACTION")) {
            return "ACTION";
        }
        return n;
    }

    private static List<AiHeuristics.AiPlayCandidate> pruneCandidates(
            List<AiHeuristics.AiPlayCandidate> candidates) {
        int limit = Math.max(8, MAX_MODEL_CANDIDATES);
        if (candidates.size() <= limit) {
            return candidates;
        }
        List<ScoredCandidate> scored = new ArrayList<>();
        int order = 0;
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            scored.add(new ScoredCandidate(candidate, candidateScore(candidate), order++));
        }
        scored.sort(Comparator
                .comparingInt(ScoredCandidate::score)
                .reversed()
                .thenComparingInt(ScoredCandidate::order));
        List<AiHeuristics.AiPlayCandidate> pruned = new ArrayList<>();
        int seq = 1;
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            AiHeuristics.AiPlayCandidate c = scored.get(i).candidate();
            pruned.add(new AiHeuristics.AiPlayCandidate("c" + seq++, c.request(), c.summary()));
        }
        AiBattleLogger.log("DeepSeek",
                "pruned candidates " + candidates.size() + " -> " + pruned.size());
        return pruned;
    }

    static int candidateScore(AiHeuristics.AiPlayCandidate candidate) {
        String summary = candidate.summary() == null ? "" : candidate.summary().toUpperCase(Locale.ROOT);
        String actionType = candidate.request() == null ? "" : String.valueOf(candidate.request().getActionType())
                .toUpperCase(Locale.ROOT);
        boolean deposit = "DEPOSIT".equals(actionType) || summary.contains("BANK ");
        int score = 0;
        if (summary.contains("COMPLETIONSCORE=1000")) {
            score += 10_000;
        } else if (summary.contains("DEPLOY")) {
            score += 4_000 + numberAfter(summary, "COMPLETIONSCORE=");
        }
        if (!deposit && summary.contains("DEAL_BREAKER")) {
            score += 9_500;
        }
        if (!deposit && summary.contains("FORCED_DEAL")) {
            score += 7_000 + numberAfter(summary, "NETSCORE=");
        }
        if (!deposit && summary.contains("STEAL_PROPERTY")) {
            score += 6_500;
        }
        if (!deposit && summary.contains("DEBT_COLLECTOR")) {
            score += 6_000 + numberAfter(summary, "EXPECTEDPAID=") * 180;
        }
        if (!deposit && summary.contains("RENT")) {
            score += 5_500 + numberAfter(summary, "EXPECTEDPAID=") * 160;
        }
        if (!deposit && summary.contains("PASS_GO")) {
            score += 4_800;
        }
        if (!deposit && summary.contains("BIRTHDAY")) {
            score += 4_000 + numberAfter(summary, "EXPECTEDPAID=") * 220;
        }
        if (!deposit && (summary.contains("HOUSE") || summary.contains("HOTEL"))) {
            score += 4_200;
        }
        if (deposit) {
            score += 1_000 + numberBefore(summary, "M.") * 120;
        }
        return score;
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

    private record ScoredCandidate(
            AiHeuristics.AiPlayCandidate candidate,
            int score,
            int order) {
    }
}
