package com.monopoly.pattern.strategy;

import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.PropertyColorProgress;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.List;
import java.util.Locale;

final class BoardEvaluator {

    private static final double INVALID_SCORE = -1_000_000_000d;

    private BoardEvaluator() {
    }

    static double stateValue(GameStateSnapshot snapshot, String actorId) {
        if (snapshot.getForceEndReason() != null) {
            return -20_000d;
        }
        String winner = naturalWinnerId(snapshot);
        if (actorId.equals(winner)) {
            return 1_000_000d;
        }
        if (winner != null && !winner.isBlank()) {
            return -1_000_000d;
        }

        GameStateSnapshot.PlayerPublicSummary self = null;
        List<GameStateSnapshot.PlayerPublicSummary> opponents = new java.util.ArrayList<>();
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            if (actorId.equals(player.getPlayerId())) {
                self = player;
            } else {
                opponents.add(player);
            }
        }
        if (self == null) {
            return INVALID_SCORE;
        }

        double selfValue = playerValue(self);
        double maxOpponent = 0d;
        double totalOpponent = 0d;
        for (GameStateSnapshot.PlayerPublicSummary opponent : opponents) {
            double value = playerValue(opponent);
            maxOpponent = Math.max(maxOpponent, value);
            totalOpponent += value;
        }
        double avgOpponent = opponents.isEmpty() ? 0d : totalOpponent / opponents.size();
        ThreatProfile threatProfile = opponentThreat(opponents);
        double threatPenalty = threatProfile.value();
        if (shouldApplyConditionalThreat(self, threatProfile)) {
            threatPenalty += threatProfile.value() * SearchConfig.CONDITIONAL_THREAT_WEIGHT;
        }
        double tempo = snapshot.getActionsRemainingThisTurn() * 18d
                + Math.max(0, 7 - self.getHandCount()) * 10d
                - snapshot.getOverflowDiscardCount() * 120d;

        return selfValue
                - maxOpponent * SearchConfig.MAX_OPPONENT_WEIGHT
                - avgOpponent * SearchConfig.AVG_OPPONENT_WEIGHT
                - threatPenalty * SearchConfig.THREAT_WEIGHT
                + tempo;
    }

    static double playerValue(GameStateSnapshot.PlayerPublicSummary player) {
        int cappedProgress = 0;
        int nearComplete = 0;
        int lockedColors = 0;
        for (PropertyColorProgress progress : player.getPropertyColorProgress()) {
            int need = Math.max(1, progress.getNeed());
            int effective = Math.max(0, progress.getEffectiveCount());
            cappedProgress += Math.min(effective, need);
            if (effective >= need) {
                lockedColors++;
            } else if (effective == need - 1) {
                nearComplete++;
            }
        }
        int completeSets = player.getCompletePropertySets();
        return completeSets * SearchConfig.COMPLETE_SET_VALUE
                + lockedColors * 1_200d
                + nearComplete * SearchConfig.NEAR_COMPLETE_VALUE
                + cappedProgress * SearchConfig.CAPPED_PROGRESS_VALUE
                + player.getPropertyCount() * SearchConfig.PROPERTY_COUNT_VALUE
                + buildingRentBonusM(player.getPropertyZoneCards()) * SearchConfig.BUILDING_RENT_BONUS_VALUE
                + player.getBankTotalValueM() * SearchConfig.BANK_VALUE
                + player.getHandCount() * 22d
                - player.getActionZoneCount() * 10d;
    }

    static int buildingRentBonusM(com.google.gson.JsonArray propertyCards) {
        if (propertyCards == null || propertyCards.isEmpty()) {
            return 0;
        }
        int bonus = 0;
        for (com.google.gson.JsonElement element : propertyCards) {
            if (!element.isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject card = element.getAsJsonObject();
            String level = card.has("buildingLevel") && !card.get("buildingLevel").isJsonNull()
                    ? card.get("buildingLevel").getAsString().trim().toUpperCase(Locale.ROOT)
                    : "";
            if ("HOUSE".equals(level)) {
                bonus += 3;
            } else if ("HOTEL".equals(level)) {
                bonus += 7;
            }
        }
        return bonus;
    }

    static ThreatProfile opponentThreat(List<GameStateSnapshot.PlayerPublicSummary> opponents) {
        ThreatProfile best = new ThreatProfile(0d, 0, 0);
        for (GameStateSnapshot.PlayerPublicSummary opponent : opponents) {
            int almost = 0;
            for (PropertyColorProgress progress : opponent.getPropertyColorProgress()) {
                int need = Math.max(1, progress.getNeed());
                int effective = Math.max(0, progress.getEffectiveCount());
                if (effective == need - 1) {
                    almost++;
                }
            }
            double value = opponent.getCompletePropertySets() * 1_700d
                    + almost * 520d
                    + buildingRentBonusM(opponent.getPropertyZoneCards()) * SearchConfig.OPPONENT_BUILDING_THREAT_VALUE
                    + opponent.getBankTotalValueM() * 18d;
            if (value > best.value()) {
                best = new ThreatProfile(value, opponent.getCompletePropertySets(), almost);
            }
        }
        return best;
    }

    static boolean shouldApplyConditionalThreat(
            GameStateSnapshot.PlayerPublicSummary self,
            ThreatProfile threatProfile) {
        if (SearchConfig.CONDITIONAL_THREAT_WEIGHT == 0d || self == null || threatProfile.value() <= 0d) {
            return false;
        }
        if (self.getCompletePropertySets() > SearchConfig.CONDITIONAL_THREAT_SELF_MAX_SETS) {
            return false;
        }
        return threatProfile.completeSets() >= SearchConfig.CONDITIONAL_THREAT_OPPONENT_MIN_SETS
                || threatProfile.nearCompleteColors() >= SearchConfig.CONDITIONAL_THREAT_OPPONENT_MIN_ALMOST;
    }

    record ThreatProfile(
            double value,
            int completeSets,
            int nearCompleteColors) {
    }

    static String naturalWinnerId(GameStateSnapshot snapshot) {
        if (snapshot == null || !snapshot.isGameOver() || snapshot.getForceEndReason() != null) {
            return null;
        }
        String winner = null;
        int winners = 0;
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            if (player.getCompletePropertySets() >= 3) {
                winner = player.getPlayerId();
                winners++;
            }
        }
        if (winners == 1) {
            return winner;
        }
        return winnerFromLastActionSummary(snapshot);
    }

    static String winnerFromLastActionSummary(GameStateSnapshot snapshot) {
        String summary = SearchConfig.trim(snapshot.getLastActionSummary()).toLowerCase(Locale.ROOT);
        if (summary.isBlank()) {
            return null;
        }
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            String displayName = SearchConfig.trim(player.getDisplayName()).toLowerCase(Locale.ROOT);
            String playerId = SearchConfig.trim(player.getPlayerId()).toLowerCase(Locale.ROOT);
            if (!displayName.isBlank() && summary.startsWith(displayName + " wins")) {
                return player.getPlayerId();
            }
            if (!playerId.isBlank() && summary.startsWith(playerId + " wins")) {
                return player.getPlayerId();
            }
        }
        return null;
    }

    static double candidateAdjustment(
            Player actor,
            AiHeuristics.AiPlayCandidate candidate) {
        if (candidate == null || candidate.request() == null) {
            return 0d;
        }
        double wildDeployAdj = wildDeployAdjustment(actor, candidate.request());
        if (wildDeployAdj != 0d) {
            return wildDeployAdj;
        }
        String actionType = SearchConfig.trim(candidate.request().getActionType()).toUpperCase(Locale.ROOT);
        boolean deposit = "DEPOSIT".equals(actionType);
        String effect = candidateEffect(actor, candidate);
        if (deposit && "PASS_GO".equals(effect)) {
            return SearchConfig.PASS_GO_DEPOSIT_BONUS;
        }
        if (!deposit && "PASS_GO".equals(effect)) {
            int handCount = actor == null ? 0 : actor.getHandCardCount();
            int overflow = Math.max(0, handCount + 2 - 7);
            return SearchConfig.PASS_GO_EXPECTED_VALUE - overflow * SearchConfig.PASS_GO_OVERFLOW_PENALTY;
        }
        if (!deposit && "BIRTHDAY".equals(effect) && SearchConfig.LOW_BIRTHDAY_PENALTY != 0d) {
            int expectedPaid = parseExpectedPaid(candidate.summary());
            if (expectedPaid > 0 && expectedPaid <= 2) {
                return SearchConfig.LOW_BIRTHDAY_PENALTY;
            }
        }
        if (!deposit && "BIRTHDAY".equals(effect) && SearchConfig.BIRTHDAY_EXPECTED_PAID_MULTIPLIER != 0d) {
            return parseExpectedPaid(candidate.summary()) * SearchConfig.BIRTHDAY_EXPECTED_PAID_MULTIPLIER;
        }
        if (!deposit && ("RENT".equals(effect) || "RENT_DUAL".equals(effect))) {
            return SearchConfig.RENT_ACTION_BONUS + parseExpectedPaid(candidate.summary()) * SearchConfig.RENT_EXPECTED_PAID_MULTIPLIER;
        }
        if (!deposit && "DEBT_COLLECTOR".equals(effect) && SearchConfig.DEBT_EXPECTED_PAID_MULTIPLIER != 0d) {
            return parseExpectedPaid(candidate.summary()) * SearchConfig.DEBT_EXPECTED_PAID_MULTIPLIER;
        }
        if (!deposit && "STEAL_PROPERTY".equals(effect)) {
            return SearchConfig.STEAL_PROPERTY_BONUS
                    + numberAfter(candidate.summary(), "completionGain=") * SearchConfig.STEAL_COMPLETION_GAIN_MULTIPLIER
                    + numberAfter(candidate.summary(), "oppCompletionLoss=") * SearchConfig.STEAL_OPP_COMPLETION_LOSS_MULTIPLIER
                    + numberAfter(candidate.summary(), "takeValue=") * SearchConfig.STEAL_TAKE_VALUE_MULTIPLIER
                    + (containsMarkerValue(candidate.summary(), "wild=", "true") ? SearchConfig.STEAL_WILD_BONUS : 0d);
        }
        if (!deposit && "FORCED_DEAL".equals(effect)) {
            return SearchConfig.FORCED_DEAL_BONUS;
        }
        if (!deposit && ("HOUSE".equals(effect) || "HOTEL".equals(effect))) {
            return SearchConfig.BUILDING_ACTION_BONUS;
        }
        return 0d;
    }

    static double wildDeployAdjustment(Player actor, PlayActionRequest request) {
        return wildDeployAdjustment(
                actor,
                request,
                SearchConfig.WILD_DEPLOY_PENALTY,
                SearchConfig.WILD_SHORT_SET_PENALTY,
                SearchConfig.WILD_COMPLETION_PENALTY,
                SearchConfig.WILD_OVERFULL_SET_PENALTY);
    }

    static double wildDeployAdjustment(
            Player actor,
            PlayActionRequest request,
            double wildDeployPenalty,
            double wildShortSetPenalty,
            double wildCompletionPenalty,
            double wildOverfullSetPenalty) {
        if (actor == null || request == null
                || !"DEPLOY".equalsIgnoreCase(SearchConfig.trim(request.getActionType()))) {
            return 0d;
        }
        String cardId = SearchConfig.trim(request.getCardId());
        String color = SearchConfig.trim(request.getTargetColorKey()).toUpperCase(Locale.ROOT);
        if (cardId.isBlank() || color.isBlank()) {
            return 0d;
        }
        PropertyWildCard wild = null;
        for (Card card : actor.getHandCardsView()) {
            if (cardId.equals(card.getId()) && card instanceof PropertyWildCard pwc) {
                wild = pwc;
                break;
            }
        }
        if (wild == null) {
            return 0d;
        }
        int need = Math.max(1, PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3));
        int current = PropertySetCalculator.effectiveCountForColor(actor.getPropertyCardsView(), color);
        double adjustment = 0d;
        if (wildDeployPenalty != 0d) {
            adjustment -= wildDeployPenalty;
        }
        if (need <= 2 && wildShortSetPenalty != 0d) {
            adjustment -= wildShortSetPenalty;
        }
        if (current >= need && wildOverfullSetPenalty != 0d) {
            adjustment -= wildOverfullSetPenalty;
        }
        if (current + 1 >= need && wildCompletionPenalty != 0d) {
            adjustment -= wildCompletionPenalty;
        }
        return adjustment;
    }

    static String actionEffect(Player actor, PlayActionRequest request) {
        if (actor == null || request == null
                || !"ACTION".equalsIgnoreCase(SearchConfig.trim(request.getActionType()))) {
            return "";
        }
        String cardId = SearchConfig.trim(request.getCardId());
        if (cardId.isBlank()) {
            return "";
        }
        for (Card card : actor.getHandCardsView()) {
            if (cardId.equals(card.getId()) && card instanceof ActionCard actionCard) {
                return SearchConfig.trim(actionCard.getEffectCode()).toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    static String effectFromSummary(String summary) {
        String s = summary == null ? "" : summary.trim();
        if (s.startsWith("Action ")) {
            int start = "Action ".length();
            int end = start;
            while (end < s.length()) {
                char ch = s.charAt(end);
                if (!Character.isLetter(ch) && ch != '_') {
                    break;
                }
                end++;
            }
            if (end > start) {
                return s.substring(start, end).toUpperCase(Locale.ROOT);
            }
        }
        if (s.startsWith("Deploy ")) {
            return "DEPLOY";
        }
        String depositPrefix = "Deposit action card ";
        if (s.startsWith(depositPrefix)) {
            int start = depositPrefix.length();
            int end = start;
            while (end < s.length()) {
                char ch = s.charAt(end);
                if (!Character.isLetter(ch) && ch != '_') {
                    break;
                }
                end++;
            }
            if (end > start) {
                return s.substring(start, end).toUpperCase(Locale.ROOT);
            }
        }
        if (s.startsWith("Deposit ")) {
            return "DEPOSIT";
        }
        return "";
    }

    static String candidateEffect(
            Player actor,
            AiHeuristics.AiPlayCandidate candidate) {
        String fromSummary = effectFromSummary(candidate == null ? "" : candidate.summary());
        if (!fromSummary.isBlank()) {
            return fromSummary;
        }
        return candidate == null ? "" : actionEffect(actor, candidate.request());
    }

    static int parseExpectedPaid(String summary) {
        String s = summary == null ? "" : summary;
        int idx = s.indexOf("expectedPaid=");
        if (idx < 0) {
            return 0;
        }
        idx += "expectedPaid=".length();
        int end = idx;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == idx) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(idx, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int numberAfter(String summary, String marker) {
        String s = summary == null ? "" : summary;
        String m = marker == null ? "" : marker;
        if (m.isBlank()) {
            return 0;
        }
        int idx = s.toUpperCase(Locale.ROOT).indexOf(m.toUpperCase(Locale.ROOT));
        if (idx < 0) {
            return 0;
        }
        idx += m.length();
        int end = idx;
        if (end < s.length() && (s.charAt(end) == '-' || s.charAt(end) == '+')) {
            end++;
        }
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == idx || (end == idx + 1 && (s.charAt(idx) == '-' || s.charAt(idx) == '+'))) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(idx, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean containsMarkerValue(String summary, String marker, String expected) {
        String s = summary == null ? "" : summary;
        String m = marker == null ? "" : marker;
        String e = expected == null ? "" : expected;
        if (m.isBlank() || e.isBlank()) {
            return false;
        }
        int idx = s.toUpperCase(Locale.ROOT).indexOf(m.toUpperCase(Locale.ROOT));
        if (idx < 0) {
            return false;
        }
        idx += m.length();
        int end = idx;
        while (end < s.length()) {
            char ch = s.charAt(end);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '-') {
                break;
            }
            end++;
        }
        return e.equalsIgnoreCase(s.substring(idx, end));
    }

    static boolean requestMayRevealHiddenCards(Player actor, PlayActionRequest request) {
        if (actor == null || request == null
                || !"ACTION".equalsIgnoreCase(SearchConfig.trim(request.getActionType()))) {
            return false;
        }
        String cardId = SearchConfig.trim(request.getCardId());
        if (cardId.isBlank()) {
            return false;
        }
        for (Card card : actor.getHandCardsView()) {
            if (cardId.equals(card.getId()) && card instanceof ActionCard actionCard) {
                return "PASS_GO".equalsIgnoreCase(SearchConfig.trim(actionCard.getEffectCode()));
            }
        }
        return false;
    }

    static String requestKey(PlayActionRequest req) {
        if (req == null) {
            return "";
        }
        return String.join("|",
                SearchConfig.trim(req.getActionType()).toUpperCase(Locale.ROOT),
                SearchConfig.trim(req.getCardId()),
                SearchConfig.trim(req.getTargetPlayerId()),
                SearchConfig.trim(req.getTargetColorKey()).toUpperCase(Locale.ROOT),
                SearchConfig.trim(req.getTargetCardId()),
                SearchConfig.trim(req.getActorCardId()),
                SearchConfig.trim(req.getTargetZone()).toUpperCase(Locale.ROOT));
    }
}