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
            Silently evaluate the tactical fields before choosing: win now, block a near-win,
            attack the leader, steal/exchange key property, protect complete sets, then cash.
            If a board-tempo candidate and passive cash candidate are both legal, choose cash only
            when the board candidate does not improve the set race or stop an opponent.
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
            - Plan over the next one or two turns: a move that blocks a near-win or creates a protected set
              is usually better than a small immediate money gain.
            - In multiplayer, pressure the current leader or any opponent on 2 complete sets; do not spend
              high-impact attacks on a trailing player unless it wins now.
            - Preserve Deal Breaker, Sly Deal, Forced Deal, Just Say No, and flexible wilds until they win,
              block a win, steal/protect a full set, or create a decisive tempo swing.
            - Strong local opponents often gain tempo by stealing and swapping property before banking cash.
              Match that tempo: if a legal Sly Deal, Forced Deal, or Deal Breaker improves your set race or
              damages the leader's set race, prefer it over passive draw/bank moves.
            - Forced Deal should not be treated as a generic exchange. Prefer swaps that take a wild/key color,
              complete or nearly complete your set, or break an opponent's near-complete set while giving away
              a low-leverage duplicate.
            - In one-vs-one and small tables, cash is only a shield. Property tempo wins games. Do not sit on
              a large bank while the opponent is building sets.
            - Once you have 2 complete sets, every play should either complete the third set, protect a complete
              set, steal/swap a missing color or wild, or stop the opponent's immediate path to 3 sets.
            - Treat complete sets and flexible wilds as high-risk assets when paying or discarding. Prefer
              overpaying from bank over breaking a complete set unless all bank is exhausted.
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
        candidates = pruneCandidates(bot, context, candidates);
        if (candidates.isEmpty()) {
            return false;
        }
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
        if (fallbackDecision.playWaiver()) {
            AiBattleLogger.log("DeepSeek",
                    bot.getPlayerId() + " used high-confidence local Just Say No: "
                            + fallbackDecision.reason());
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
            return AiHeuristics.AiResponseDecision.play(
                    fallbackDecision.request(),
                    "DeepSeek chose Just Say No after board-risk evaluation.");
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
        return choosePayment(bot, null, null, amountDue, fallbackChoice);
    }

    @Override
    public PaymentSettlement.PaymentChoice choosePayment(
            AIPlayer bot,
            GameContext context,
            Player creditor,
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
            String prompt = buildPaymentPrompt(bot, context, creditor, amountDue, payable, fallbackChoice);
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
            PaymentSettlement.PaymentChoice chosenChoice =
                    new PaymentSettlement.PaymentChoice(List.copyOf(chosen), sum);
            if (isWorsePayment(bot, amountDue, chosenChoice, fallbackChoice)) {
                AiBattleLogger.log("DeepSeek",
                        bot.getPlayerId() + " payment protected board; fallback="
                                + paymentChoiceScore(bot, amountDue, fallbackChoice)
                                + " model=" + paymentChoiceScore(bot, amountDue, chosenChoice));
                return fallbackChoice;
            }
            return chosenChoice;
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
        return chooseOverflowDiscards(bot, null, limit, fallbackCards);
    }

    @Override
    public List<Card> chooseOverflowDiscards(
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards) {
        if (!DeepSeekClient.enabled() || bot == null || bot.getHandCardCount() <= limit) {
            return fallbackCards;
        }
        int need = bot.getHandCardCount() - limit;
        try {
            String prompt = buildDiscardPrompt(bot, context, limit, need, fallbackCards);
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

    static String buildUserPrompt(
            AIPlayer bot,
            GameContext context,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        JsonObject root = baseDecisionPrompt(
                bot,
                context,
                "PLAY_CARD",
                "Choose one legal Monopoly Deal play candidate.",
                "{\"candidateId\":\"c1\"}");
        JsonObject decision = root.getAsJsonObject("decision");
        decision.add("candidateSelectionProtocol", candidateSelectionProtocolJson(bot, context));
        JsonArray cands = new JsonArray();
        for (AiHeuristics.AiPlayCandidate c : candidates) {
            JsonObject row = new JsonObject();
            row.addProperty("id", c.id());
            row.addProperty("summary", compactSummary(c.summary()));
            row.add("tactics", candidateTacticsJson(bot, context, c));
            cands.add(row);
        }
        decision.add("legalCandidates", cands);
        return root.toString();
    }

    static String buildResponsePrompt(
            AIPlayer bot,
            GameContext context,
            boolean counterRole,
            AiHeuristics.AiResponseDecision fallbackDecision) {
        JsonObject root = baseDecisionPrompt(
                bot,
                context,
                "JUST_SAY_NO",
                "Decide whether to play Just Say No in Monopoly Deal.",
                "{\"playJustSayNo\":true}");
        JsonObject decision = root.getAsJsonObject("decision");
        decision.addProperty("counterRole", counterRole);
        decision.addProperty("localRecommendationPlayJustSayNo", fallbackDecision.playWaiver());
        decision.addProperty("localRecommendationReason", fallbackDecision.reason());
        return root.toString();
    }

    static String buildPaymentPrompt(
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            List<Card> payable,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        JsonObject root = baseDecisionPrompt(
                bot,
                context,
                "PAYMENT",
                "Choose bank/property cards to pay a Monopoly Deal charge.",
                "{\"cardIds\":[\"card-id\"]}");
        JsonObject decision = root.getAsJsonObject("decision");
        decision.addProperty("amountDueM", amountDue);
        decision.addProperty("creditorPlayerId", creditor == null ? null : creditor.getPlayerId());
        decision.addProperty("creditorName", creditor == null ? null : creditor.getDisplayName());
        decision.addProperty("paymentRule",
                "Pay from bank/properties only. No change is returned. Prefer bank over property. Avoid breaking complete sets or paying wild/key property unless all bank is exhausted.");
        decision.add("payableCards", paymentCardListJson(bot, payable));
        JsonArray fallback = new JsonArray();
        if (fallbackChoice != null) {
            for (Card card : fallbackChoice.cards()) {
                fallback.add(card.getId());
            }
        }
        decision.add("localFallbackCardIds", fallback);
        decision.addProperty("localFallbackBoardRisk",
                paymentChoiceScore(bot, amountDue, fallbackChoice));
        return root.toString();
    }

    static String buildDiscardPrompt(
            AIPlayer bot,
            GameContext context,
            int limit,
            int need,
            List<Card> fallbackCards) {
        JsonObject root = baseDecisionPrompt(
                bot,
                context,
                "OVERFLOW_DISCARD",
                "Choose hand cards to discard down to Monopoly Deal hand limit.",
                "{\"cardIds\":[\"card-id\"]}");
        JsonObject decision = root.getAsJsonObject("decision");
        decision.addProperty("handLimit", limit);
        decision.addProperty("discardCount", need);
        decision.add("legalDiscardCards", cardListJson(bot.getHandCardsView(), false));
        JsonArray fallback = new JsonArray();
        if (fallbackCards != null) {
            for (Card card : fallbackCards) {
                fallback.add(card.getId());
            }
        }
        decision.add("localFallbackCardIds", fallback);
        return root.toString();
    }

    private static JsonObject baseDecisionPrompt(
            AIPlayer bot,
            GameContext context,
            String decisionKind,
            String task,
            String output) {
        JsonObject root = new JsonObject();
        root.addProperty("promptVersion", teamAwareMode()
                ? "deepseek-decision-context-v5-tempo-team-aware"
                : "deepseek-decision-context-v5-tempo");
        root.addProperty("modelRequested", DeepSeekClient.model());
        root.addProperty("rule", "Win immediately at 3 complete property sets. Max 3 plays per turn.");
        root.addProperty("strategy", STRATEGY_CONTEXT);
        root.add("evaluationMode", evaluationModeJson(bot));
        root.add("gameMeta", gameMetaJson(bot, context, decisionKind));
        root.add("self", playerJson(bot, true));
        root.add("players", playersJson(bot, context));
        root.add("riskAssessment", riskAssessmentJson(bot, context));
        root.add("effectStack", effectStackJson(context));

        JsonObject decision = new JsonObject();
        decision.addProperty("kind", decisionKind);
        decision.addProperty("task", task);
        decision.addProperty("output", output);
        root.add("decision", decision);
        return root;
    }

    private static JsonArray candidateSelectionProtocolJson(AIPlayer bot, GameContext context) {
        JsonArray arr = new JsonArray();
        arr.add("If any candidate wins immediately by reaching 3 complete sets, choose it.");
        arr.add("If an opponent has 2+ sets, choose a candidate that blocks, steals, swaps, rents, or Deal Breaks them when legal.");
        arr.add("If you already have 2 sets, choose the move that most directly creates or protects the third set; do not bank unless no tempo play exists.");
        arr.add("Prefer property swing over passive cash: key steals/exchanges beat banking unless cash prevents an immediate loss.");
        arr.add("Against hard/local opponents, expect immediate counter-steals. Prioritize taking wilds, completing small sets, and breaking their highest-progress color.");
        arr.add("Attack the current leader before a trailing player unless the trailing target gives you an immediate set.");
        arr.add("Keep Deal Breaker, Sly Deal, Forced Deal, Just Say No, and wilds for decisive board swings; do not bank them casually.");
        arr.add("Use Pass Go mainly when no high-impact property/rent candidate is available or when it can find missing combo pieces.");
        if (teamAwareMode()) {
            arr.add("Team-aware evaluation is enabled: do not attack same-team LLM players with Deal Breaker, Sly Deal, Forced Deal, rent, or Debt Collector. A same-team LLM win is team-positive.");
            arr.add("When choosing between similar board swings, prefer attacking a hard/local opponent over a same-team LLM opponent.");
        }
        return arr;
    }

    private static JsonObject evaluationModeJson(AIPlayer bot) {
        JsonObject mode = new JsonObject();
        mode.addProperty("teamAware", teamAwareMode());
        mode.addProperty("botTeam", teamOf(bot));
        if (teamAwareMode()) {
            mode.addProperty("sameTeamPolicy",
                    "Treat same-team LLM players as allies for evaluation. Do not give hard/local opponents tempo by weakening another LLM; a same-team LLM win is a good outcome in team-aware evaluation.");
        }
        return mode;
    }

    private static JsonObject gameMetaJson(AIPlayer bot, GameContext context, String decisionKind) {
        JsonObject meta = new JsonObject();
        List<Player> players = playersForContext(bot, context);
        String currentTurnPlayerId = context == null ? null : context.getCurrentTurnPlayerId();
        String phase = context == null ? "UNKNOWN" : context.getCurrentTurnPhase();
        int round = context == null ? 1 : context.getRoundNumber();
        int used = context == null ? 0 : context.getCurrentTurnActionCount();
        int max = context == null ? 3 : context.getMaxActionsPerTurn();
        meta.addProperty("playerCount", players.size());
        meta.addProperty("roundNumber", Math.max(1, round));
        meta.addProperty("decisionKind", decisionKind);
        meta.addProperty("decisionPlayerId", bot == null ? null : bot.getPlayerId());
        meta.addProperty("currentTurnPlayerId", currentTurnPlayerId);
        meta.addProperty("turnPhase", phase);
        meta.addProperty("actionsUsedThisTurn", used);
        meta.addProperty("actionsRemainingThisTurn",
                context == null ? Math.max(0, max - used) : context.remainingTurnActions());
        meta.addProperty("maxActionsPerTurn", max);
        return meta;
    }

    private static JsonArray playersJson(AIPlayer bot, GameContext context) {
        JsonArray players = new JsonArray();
        for (Player p : playersForContext(bot, context)) {
            JsonObject row = playerJson(p, false);
            row.addProperty("isSelf", p == bot);
            players.add(row);
        }
        return players;
    }

    private static List<Player> playersForContext(AIPlayer bot, GameContext context) {
        if (context != null && !context.getPlayers().isEmpty()) {
            return context.getPlayers();
        }
        return bot == null ? List.of() : List.of(bot);
    }

    private static JsonObject candidateTacticsJson(
            AIPlayer bot,
            GameContext context,
            AiHeuristics.AiPlayCandidate candidate) {
        JsonObject tactics = new JsonObject();
        PlayActionRequest req = candidate == null ? null : candidate.request();
        String summary = candidate == null || candidate.summary() == null
                ? "" : candidate.summary().toUpperCase(Locale.ROOT);
        String actionType = req == null || req.getActionType() == null
                ? "" : req.getActionType().trim().toUpperCase(Locale.ROOT);
        String targetPlayerId = req == null ? null : req.getTargetPlayerId();
        Player target = findPlayer(playersForContext(bot, context), targetPlayerId);

        JsonArray tags = new JsonArray();
        boolean deposit = "DEPOSIT".equals(actionType) || summary.contains("BANK ");
        boolean deploy = "DEPLOY".equals(actionType) || summary.contains("DEPLOY");
        boolean dealBreaker = summary.contains("DEAL_BREAKER");
        boolean forcedDeal = summary.contains("FORCED_DEAL");
        boolean slyDeal = summary.contains("STEAL_PROPERTY");
        boolean rent = summary.contains("RENT") || summary.contains("BIRTHDAY")
                || summary.contains("DEBT_COLLECTOR");
        boolean passGo = summary.contains("PASS_GO");
        boolean completesSet = summary.contains("COMPLETIONSCORE=1000");
        if (completesSet) {
            tags.add("creates-or-completes-set");
        }
        if (deploy) {
            tags.add("property-development");
        }
        if (dealBreaker) {
            tags.add("deal-breaker-full-set-swing");
        }
        if (forcedDeal) {
            tags.add("forced-deal-property-swing");
        }
        if (slyDeal) {
            tags.add("sly-deal-property-acquisition");
        }
        if (rent) {
            tags.add("cash-pressure");
        }
        if (passGo) {
            tags.add("card-draw-tempo");
        }
        if (deposit) {
            tags.add("passive-bank-cash");
        }

        int targetSets = target == null ? 0 : target.countCompletePropertySets();
        boolean targetLeader = target != null && isLeader(target, playersForContext(bot, context));
        boolean targetNearWin = target != null && targetSets >= 2;
        boolean targetSameTeam = teamAwareMode() && sameTeam(bot, target);
        boolean targetHardOpponent = teamAwareMode()
                && target != null
                && "hard".equals(teamOf(target))
                && !sameTeam(bot, target);
        boolean hardOrLocalTarget = target != null
                && !sameTeam(bot, target)
                && ("hard".equals(teamOf(target)) || "human".equals(teamOf(target)));
        boolean selfNearWin = bot != null && bot.countCompletePropertySets() >= 2;
        if (targetLeader) {
            tags.add("attacks-current-leader");
        }
        if (targetNearWin) {
            tags.add("blocks-near-win-opponent");
        }
        if (targetSameTeam) {
            tags.add("same-team-target");
        }
        if (targetHardOpponent) {
            tags.add("hard-opponent-target");
        }
        if ((forcedDeal || slyDeal || dealBreaker) && target == bot) {
            tags.add("self-target-check");
        }
        if (selfNearWin && !deposit) {
            tags.add("self-near-win-tempo");
        }
        if (hardOrLocalTarget && (forcedDeal || slyDeal || dealBreaker || rent)) {
            tags.add("pressures-hard-local-opponent");
        }

        tactics.addProperty("localScore", candidateScore(candidate));
        tactics.addProperty("actionType", actionType);
        tactics.addProperty("selfCompleteSets", bot == null ? 0 : bot.countCompletePropertySets());
        tactics.addProperty("selfNearWin", selfNearWin);
        tactics.addProperty("targetPlayerId", targetPlayerId);
        tactics.addProperty("targetCompleteSets", targetSets);
        tactics.addProperty("targetIsLeader", targetLeader);
        tactics.addProperty("targetNearWin", targetNearWin);
        tactics.addProperty("targetSameTeam", targetSameTeam);
        tactics.addProperty("targetHardOpponent", targetHardOpponent);
        tactics.addProperty("targetHardOrLocalOpponent", hardOrLocalTarget);
        tactics.addProperty("netScore", numberAfter(summary, "NETSCORE="));
        tactics.addProperty("materialGain", numberAfter(summary, "MATERIALGAIN="));
        tactics.addProperty("completionGain", numberAfter(summary, "COMPLETIONGAIN="));
        tactics.addProperty("opponentCompletionLoss", numberAfter(summary, "OPPCOMPLETIONLOSS="));
        tactics.addProperty("expectedPaidM", numberAfter(summary, "EXPECTEDPAID="));
        tactics.addProperty("isPassiveCash", deposit);
        tactics.add("tags", tags);
        tactics.addProperty("modelHint", candidateHint(
                deposit,
                completesSet,
                targetLeader,
                targetNearWin,
                targetSameTeam,
                targetHardOpponent,
                hardOrLocalTarget,
                selfNearWin,
                dealBreaker,
                forcedDeal,
                slyDeal,
                passGo));
        return tactics;
    }

    private static String candidateHint(
            boolean deposit,
            boolean completesSet,
            boolean targetLeader,
            boolean targetNearWin,
            boolean targetSameTeam,
            boolean targetHardOpponent,
            boolean hardOrLocalTarget,
            boolean selfNearWin,
            boolean dealBreaker,
            boolean forcedDeal,
            boolean slyDeal,
            boolean passGo) {
        if (completesSet) {
            return "High priority: this improves the set race directly.";
        }
        if (selfNearWin && (forcedDeal || slyDeal || dealBreaker)) {
            return "High priority: you are near winning; use board swings to complete or protect the third set.";
        }
        if (targetSameTeam && (dealBreaker || forcedDeal || slyDeal)) {
            return "Do not choose in team-aware evaluation: this weakens same-team LLM board tempo.";
        }
        if (targetHardOpponent && (dealBreaker || forcedDeal || slyDeal)) {
            return "High priority team-aware board swing: takes tempo from a hard/local opponent.";
        }
        if (hardOrLocalTarget && (dealBreaker || forcedDeal || slyDeal)) {
            return "High priority board swing against a hard/local opponent; reduce their property tempo before banking.";
        }
        if (dealBreaker) {
            return "High priority if target owns a complete set, especially leader or near-win opponent.";
        }
        if (forcedDeal || slyDeal) {
            if (targetLeader || targetNearWin) {
                return "High priority board swing: attacks leader/near-win opponent property tempo.";
            }
            return "Prefer only if it creates your set progress or steals a key wild/color.";
        }
        if (deposit) {
            return "Low priority unless no board-swing or set-progress candidate exists.";
        }
        if (passGo) {
            return "Medium priority: draw for options after checking property/rent swings first.";
        }
        return "Compare resulting board position against the current leader and set race.";
    }

    private static Player findPlayer(List<Player> players, String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        for (Player p : players) {
            if (playerId.equals(p.getPlayerId())) {
                return p;
            }
        }
        return null;
    }

    private static boolean isLeader(Player player, List<Player> players) {
        if (player == null) {
            return false;
        }
        Player leader = null;
        for (Player p : players) {
            if (leader == null
                    || p.countCompletePropertySets() > leader.countCompletePropertySets()
                    || (p.countCompletePropertySets() == leader.countCompletePropertySets()
                    && p.totalBankValueM() > leader.totalBankValueM())) {
                leader = p;
            }
        }
        return leader == player;
    }

    private static boolean teamAwareMode() {
        return Boolean.parseBoolean(System.getProperty("monopoly.deepseek.teamAware", "false"));
    }

    private static boolean sameTeam(Player a, Player b) {
        String left = teamOf(a);
        String right = teamOf(b);
        return !left.isBlank() && left.equals(right);
    }

    private static String teamOf(Player player) {
        if (player == null || player.getDisplayName() == null) {
            return "";
        }
        String name = player.getDisplayName().toLowerCase(Locale.ROOT);
        if (name.contains("deepseek") || name.contains("llm")) {
            return "llm";
        }
        if (name.contains("hard")) {
            return "hard";
        }
        if (name.contains("normal")) {
            return "normal";
        }
        if (name.contains("easy")) {
            return "easy";
        }
        if (name.contains("player") || name.contains("human")) {
            return "human";
        }
        return "";
    }

    private static JsonObject riskAssessmentJson(AIPlayer bot, GameContext context) {
        JsonObject risk = new JsonObject();
        List<Player> players = playersForContext(bot, context);
        int selfSets = bot == null ? 0 : bot.countCompletePropertySets();
        risk.addProperty("selfCompleteSets", selfSets);
        risk.addProperty("selfSetsNeededToWin", Math.max(0, 3 - selfSets));

        Player leader = null;
        int leaderSets = -1;
        int maxOpponentSets = 0;
        JsonArray nearWin = new JsonArray();
        for (Player p : players) {
            int sets = p.countCompletePropertySets();
            if (leader == null
                    || sets > leaderSets
                    || (sets == leaderSets && p.totalBankValueM() > leader.totalBankValueM())) {
                leader = p;
                leaderSets = sets;
            }
            if (p != bot) {
                maxOpponentSets = Math.max(maxOpponentSets, sets);
                if (sets >= 2) {
                    JsonObject threat = new JsonObject();
                    threat.addProperty("playerId", p.getPlayerId());
                    threat.addProperty("name", p.getDisplayName());
                    threat.addProperty("completeSets", sets);
                    threat.addProperty("setsNeededToWin", Math.max(0, 3 - sets));
                    threat.addProperty("bankM", p.totalBankValueM());
                    nearWin.add(threat);
                }
            }
        }
        risk.addProperty("leaderPlayerId", leader == null ? null : leader.getPlayerId());
        risk.addProperty("leaderCompleteSets", Math.max(0, leaderSets));
        risk.addProperty("maxOpponentCompleteSets", maxOpponentSets);
        risk.add("opponentsNearWin", nearWin);
        risk.addProperty("planningPriority",
                "1 win now; 2 block any opponent at 2+ sets or decisive steal/rent; "
                        + "3 complete/protect own sets and missing wild colors; "
                        + "4 preserve high-leverage actions and Just Say No for board swings; "
                        + "5 improve bank only when it does not delay set tempo.");
        risk.addProperty("shortSightGuard",
                "Before selecting a candidate, compare board position after this play, not just immediate cash. "
                        + "A large bank without complete sets is usually losing to hard opponents.");
        return risk;
    }

    private static JsonArray effectStackJson(GameContext context) {
        JsonArray stack = new JsonArray();
        if (context == null) {
            return stack;
        }
        for (com.monopoly.model.effects.EffectStackEntry entry : context.getEffectStackView()) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", entry.getKind().name());
            row.addProperty("actorPlayerId", entry.getActorPlayerId());
            row.addProperty("tenantPlayerId", entry.getTenantPlayerId());
            row.addProperty("colorKey", entry.getColorKey());
            row.addProperty("amountDue", entry.getAmountDue());
            row.addProperty("waiverTargetEntryId", entry.getWaiverTargetEntryId());
            stack.add(row);
        }
        return stack;
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
            o.add("handCards", cardListJson(p.getHandCardsView(), false));
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

    private static JsonArray paymentCardListJson(Player owner, List<Card> cards) {
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
            boolean property = card instanceof PropertyCard;
            row.addProperty("zone", property ? "PROPERTY" : "BANK");
            row.addProperty("paymentRisk", paymentCardRisk(owner, card));
            if (card instanceof PropertyCard pc) {
                String color = normalizeColor(pc);
                row.addProperty("color", color);
                int count = owner == null ? 0
                        : PropertySetCalculator.effectiveCountForColor(owner.getPropertyCardsView(), color);
                int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3);
                row.addProperty("colorCountBeforePayment", count);
                row.addProperty("colorNeed", need);
                row.addProperty("breaksCompleteSet", count >= need);
                row.addProperty("isWild", pc instanceof PropertyWildCard);
            }
            arr.add(row);
        }
        return arr;
    }

    private static int paymentCardRisk(Player owner, Card card) {
        if (!(card instanceof PropertyCard pc)) {
            return 0;
        }
        String color = normalizeColor(pc);
        int count = owner == null ? 0
                : PropertySetCalculator.effectiveCountForColor(owner.getPropertyCardsView(), color);
        int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3);
        int risk = 100 + PayableCards.valueOf(card) * 10;
        if (count >= need) {
            risk += 1_000;
        } else if (count == need - 1) {
            risk += 500;
        }
        if (pc instanceof PropertyWildCard) {
            risk += 350;
        }
        return risk;
    }

    private static boolean isWorsePayment(
            Player owner,
            int amountDue,
            PaymentSettlement.PaymentChoice modelChoice,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        if (modelChoice == null || fallbackChoice == null) {
            return false;
        }
        int modelScore = paymentChoiceScore(owner, amountDue, modelChoice);
        int fallbackScore = paymentChoiceScore(owner, amountDue, fallbackChoice);
        return modelScore > fallbackScore + 250;
    }

    private static int paymentChoiceScore(
            Player owner,
            int amountDue,
            PaymentSettlement.PaymentChoice choice) {
        if (choice == null) {
            return 0;
        }
        int score = Math.max(0, choice.amountPaid() - amountDue) * 40;
        for (Card card : choice.cards()) {
            score += paymentCardRisk(owner, card);
        }
        return score;
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

    static List<AiHeuristics.AiPlayCandidate> pruneCandidates(
            AIPlayer bot,
            GameContext context,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        candidates = filterSameTeamDestructiveCandidates(bot, context, candidates);
        int limit = Math.max(8, MAX_MODEL_CANDIDATES);
        if (candidates.size() <= limit) {
            return candidates;
        }
        List<ScoredCandidate> scored = new ArrayList<>();
        int order = 0;
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            scored.add(new ScoredCandidate(candidate, candidateScore(bot, context, candidate), order++));
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

    private static List<AiHeuristics.AiPlayCandidate> filterSameTeamDestructiveCandidates(
            AIPlayer bot,
            GameContext context,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        if (!teamAwareMode() || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<AiHeuristics.AiPlayCandidate> filtered = new ArrayList<>();
        int removed = 0;
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            if (sameTeamDestructiveCandidate(bot, context, candidate)) {
                removed++;
                continue;
            }
            filtered.add(candidate);
        }
        if (removed == 0) {
            return candidates;
        }
        AiBattleLogger.log("DeepSeek",
                "team-aware removed same-team destructive candidates: " + removed);
        return renumberCandidates(filtered);
    }

    private static boolean sameTeamDestructiveCandidate(
            AIPlayer bot,
            GameContext context,
            AiHeuristics.AiPlayCandidate candidate) {
        if (bot == null || candidate == null || candidate.request() == null) {
            return false;
        }
        String targetPlayerId = candidate.request().getTargetPlayerId();
        if (targetPlayerId == null || targetPlayerId.isBlank()) {
            return false;
        }
        Player target = findPlayer(playersForContext(bot, context), targetPlayerId);
        if (!sameTeam(bot, target)) {
            return false;
        }
        String summary = candidate.summary() == null ? "" : candidate.summary().toUpperCase(Locale.ROOT);
        return summary.contains("DEAL_BREAKER")
                || summary.contains("FORCED_DEAL")
                || summary.contains("STEAL_PROPERTY")
                || summary.contains("DEBT_COLLECTOR")
                || summary.contains("RENT");
    }

    private static List<AiHeuristics.AiPlayCandidate> renumberCandidates(
            List<AiHeuristics.AiPlayCandidate> candidates) {
        List<AiHeuristics.AiPlayCandidate> out = new ArrayList<>();
        int seq = 1;
        for (AiHeuristics.AiPlayCandidate c : candidates) {
            out.add(new AiHeuristics.AiPlayCandidate("c" + seq++, c.request(), c.summary()));
        }
        return out;
    }

    static int candidateScore(AiHeuristics.AiPlayCandidate candidate) {
        return candidateScore(null, null, candidate);
    }

    static int candidateScore(
            AIPlayer bot,
            GameContext context,
            AiHeuristics.AiPlayCandidate candidate) {
        String summary = candidate.summary() == null ? "" : candidate.summary().toUpperCase(Locale.ROOT);
        String actionType = candidate.request() == null ? "" : String.valueOf(candidate.request().getActionType())
                .toUpperCase(Locale.ROOT);
        boolean deposit = "DEPOSIT".equals(actionType) || summary.contains("BANK ");
        boolean cashPressure = summary.contains("DEBT_COLLECTOR")
                || summary.contains("RENT")
                || summary.contains("BIRTHDAY");
        int selfSets = bot == null ? 0 : bot.countCompletePropertySets();
        int selfBank = bot == null ? 0 : bot.totalBankValueM();
        int maxOpponentSets = maxOpponentCompleteSets(bot, context);
        boolean cashSaturated = bot != null && selfBank >= 12 && selfSets <= maxOpponentSets;
        int score = 0;
        if (summary.contains("COMPLETIONSCORE=1000")) {
            score += 12_000;
        } else if (summary.contains("DEPLOY")) {
            score += 5_200 + numberAfter(summary, "COMPLETIONSCORE=") * 2;
        }
        if (!deposit && summary.contains("DEAL_BREAKER")) {
            score += 10_800;
        }
        if (!deposit && summary.contains("FORCED_DEAL")) {
            score += 8_600 + numberAfter(summary, "NETSCORE=") * 2;
        }
        if (!deposit && summary.contains("STEAL_PROPERTY")) {
            score += 8_000;
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
        if (cashSaturated && deposit) {
            score -= 2_000;
        }
        if (cashSaturated && cashPressure) {
            score -= 1_200;
        }
        if (bot != null && selfSets >= 2 && (summary.contains("DEPLOY")
                || summary.contains("DEAL_BREAKER")
                || summary.contains("FORCED_DEAL")
                || summary.contains("STEAL_PROPERTY"))) {
            score += 1_500;
        }
        return score;
    }

    private static int maxOpponentCompleteSets(AIPlayer bot, GameContext context) {
        int max = 0;
        for (Player p : playersForContext(bot, context)) {
            if (p == null || p == bot) {
                continue;
            }
            max = Math.max(max, p.countCompletePropertySets());
        }
        return max;
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
