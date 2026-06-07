package com.monopoly.pattern.strategy;

import com.monopoly.dto.PlayActionRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CandidatePruner {

    private CandidatePruner() {
    }

    static List<AiHeuristics.AiPlayCandidate> prune(
            List<AiHeuristics.AiPlayCandidate> candidates,
            PlayActionRequest hardRequest) {
        int limit = Math.max(4, SearchConfig.MAX_CANDIDATES);
        return pruneToLimit(candidates, hardRequest, limit);
    }

    static List<AiHeuristics.AiPlayCandidate> pruneToLimit(
            List<AiHeuristics.AiPlayCandidate> candidates,
            PlayActionRequest hardRequest,
            int limit) {
        if (candidates.size() <= limit) {
            return candidates;
        }
        List<AiHeuristics.AiPlayCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(
                (AiHeuristics.AiPlayCandidate candidate) ->
                        prunePriorityScore(candidate)).reversed());
        List<AiHeuristics.AiPlayCandidate> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (AiHeuristics.AiPlayCandidate candidate : sorted) {
            if (out.size() >= limit) {
                break;
            }
            out.add(candidate);
            keys.add(BoardEvaluator.requestKey(candidate.request()));
        }
        addStructuredTacticalCandidates(candidates, out, keys, limit);
        addReservedTacticalCandidates(candidates, out, keys, limit);
        String hardKey = BoardEvaluator.requestKey(hardRequest);
        if (!hardKey.isBlank() && !keys.contains(hardKey)) {
            for (AiHeuristics.AiPlayCandidate candidate : candidates) {
                if (hardKey.equals(BoardEvaluator.requestKey(candidate.request()))) {
                    out.add(candidate);
                    break;
                }
            }
        }
        return out;
    }

    static double prunePriorityScore(AiHeuristics.AiPlayCandidate candidate) {
        double score = DeepSeekAiPlayStrategy.candidateScore(candidate);
        if (SearchConfig.STRUCTURED_PRUNE_PRIORITY_WEIGHT != 0d) {
            score += structuredTacticalPriority(candidate) * SearchConfig.STRUCTURED_PRUNE_PRIORITY_WEIGHT;
        }
        return score;
    }

    static void addStructuredTacticalCandidates(
            List<AiHeuristics.AiPlayCandidate> candidates,
            List<AiHeuristics.AiPlayCandidate> out,
            Set<String> keys,
            int limit) {
        if (SearchConfig.STRUCTURED_TACTICAL_RESERVED_CANDIDATES <= 0 || candidates == null || candidates.isEmpty()) {
            return;
        }
        int hardLimit = Math.max(limit, limit + SearchConfig.STRUCTURED_TACTICAL_RESERVED_CANDIDATES);
        List<CandidatePriority> tactical = new ArrayList<>();
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            int priority = structuredTacticalPriority(candidate);
            if (priority > 0) {
                tactical.add(new CandidatePriority(candidate, priority));
            }
        }
        tactical.sort(Comparator
                .comparingInt(CandidatePriority::priority)
                .reversed()
                .thenComparing(item -> DeepSeekAiPlayStrategy.candidateScore(item.candidate()), Comparator.reverseOrder()));
        for (CandidatePriority item : tactical) {
            if (out.size() >= hardLimit) {
                return;
            }
            String key = BoardEvaluator.requestKey(item.candidate().request());
            if (key.isBlank() || keys.contains(key)) {
                continue;
            }
            out.add(item.candidate());
            keys.add(key);
        }
    }

    static int structuredTacticalPriority(AiHeuristics.AiPlayCandidate candidate) {
        if (candidate == null || candidate.request() == null) {
            return 0;
        }
        String effect = BoardEvaluator.candidateEffect(null, candidate);
        if ("STEAL_PROPERTY".equals(effect)) {
            int completionGain = BoardEvaluator.numberAfter(candidate.summary(), "completionGain=");
            int oppCompletionLoss = BoardEvaluator.numberAfter(candidate.summary(), "oppCompletionLoss=");
            int takeValue = BoardEvaluator.numberAfter(candidate.summary(), "takeValue=");
            boolean wild = BoardEvaluator.containsMarkerValue(candidate.summary(), "wild=", "true");
            int priority = completionGain * 30
                    + oppCompletionLoss * 22
                    + takeValue * 8
                    + (wild ? 26 : 0);
            if (completionGain > 0 || oppCompletionLoss > 0 || wild) {
                priority += 60;
            }
            return priority;
        }
        if ("FORCED_DEAL".equals(effect)) {
            int netScore = BoardEvaluator.numberAfter(candidate.summary(), "netScore=");
            int completionGain = BoardEvaluator.numberAfter(candidate.summary(), "completionGain=");
            int oppCompletionLoss = BoardEvaluator.numberAfter(candidate.summary(), "oppCompletionLoss=");
            int materialGain = BoardEvaluator.numberAfter(candidate.summary(), "materialGain=");
            int priority = netScore
                    + completionGain * 24
                    + oppCompletionLoss * 18
                    + materialGain * 2;
            if (completionGain > 0 || oppCompletionLoss > 0 || netScore > 0) {
                priority += 70;
            }
            return priority;
        }
        if ("DEAL_BREAKER".equals(effect)) {
            return 160;
        }
        return 0;
    }

    static void addReservedTacticalCandidates(
            List<AiHeuristics.AiPlayCandidate> candidates,
            List<AiHeuristics.AiPlayCandidate> out,
            Set<String> keys,
            int limit) {
        if (SearchConfig.TACTICAL_RESERVED_CANDIDATES <= 0 || candidates == null || candidates.isEmpty()) {
            return;
        }
        int hardLimit = Math.max(limit, limit + SearchConfig.TACTICAL_RESERVED_CANDIDATES);
        List<AiHeuristics.AiPlayCandidate> tactical = new ArrayList<>();
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            if (isReservedTacticalCandidate(candidate)) {
                tactical.add(candidate);
            }
        }
        tactical.sort(Comparator.comparingDouble(
                (AiHeuristics.AiPlayCandidate candidate) ->
                        DeepSeekAiPlayStrategy.candidateScore(candidate)).reversed());
        for (AiHeuristics.AiPlayCandidate candidate : tactical) {
            if (out.size() >= hardLimit) {
                return;
            }
            String key = BoardEvaluator.requestKey(candidate.request());
            if (key.isBlank() || keys.contains(key)) {
                continue;
            }
            out.add(candidate);
            keys.add(key);
        }
    }

    static boolean isReservedTacticalCandidate(AiHeuristics.AiPlayCandidate candidate) {
        if (candidate == null || candidate.request() == null) {
            return false;
        }
        String summary = candidate.summary() == null
                ? ""
                : candidate.summary().toUpperCase(Locale.ROOT);
        if (summary.contains("DEAL_BREAKER") || summary.contains("FORCED_DEAL")
                || summary.contains("STEAL_PROPERTY")) {
            return true;
        }
        if (!"DEPLOY".equalsIgnoreCase(SearchConfig.trim(candidate.request().getActionType()))) {
            return false;
        }
        int completionScore = BoardEvaluator.numberAfter(summary, "COMPLETIONSCORE=");
        return completionScore >= 100;
    }

    static boolean isCorrectorTacticalCandidate(AiHeuristics.AiPlayCandidate candidate) {
        if (candidate == null || candidate.request() == null) {
            return false;
        }
        String actionType = SearchConfig.trim(candidate.request().getActionType()).toUpperCase(Locale.ROOT);
        if (!SearchConfig.CORRECTOR_ALLOWED_ACTION_TYPES.contains(actionType)) {
            return false;
        }
        if (!"ACTION".equals(actionType)) {
            return true;
        }
        String effect = BoardEvaluator.candidateEffect(null, candidate);
        return !effect.isBlank() && SearchConfig.CORRECTOR_ALLOWED_EFFECTS.contains(effect);
    }

    record CandidatePriority(
            AiHeuristics.AiPlayCandidate candidate,
            int priority) {
    }
}