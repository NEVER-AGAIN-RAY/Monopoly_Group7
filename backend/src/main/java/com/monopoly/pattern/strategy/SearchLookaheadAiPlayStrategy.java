package com.monopoly.pattern.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.pattern.strategy.decision.DecisionTraceSink;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SearchLookaheadAiPlayStrategy implements AiPlayStrategy, AiChoiceAdvisor {

    private static final double INVALID_SCORE = -1_000_000_000d;

    private final HardAiPlayStrategy fallback = new HardAiPlayStrategy();
    private final DecisionTraceSink traceSink;
    private final String traceSessionId;
    private final LocalRankerAiPlayStrategy.RankerModel correctorModel;
    private final String correctorModelPath;

    public SearchLookaheadAiPlayStrategy() {
        this(DecisionTraceSink.NONE, "");
    }

    public SearchLookaheadAiPlayStrategy(
            DecisionTraceSink traceSink,
            String traceSessionId) {
        this.traceSink = traceSink == null ? DecisionTraceSink.NONE : traceSink;
        this.traceSessionId = traceSessionId == null ? "" : traceSessionId;
        this.correctorModelPath = SearchConfig.CORRECTOR_MODEL_PATH;
        this.correctorModel = correctorModelPath.isBlank()
                ? null
                : LocalRankerAiPlayStrategy.RankerModel.load(Path.of(correctorModelPath));
    }

    public static JsonObject effectiveConfigSnapshot() {
        return SearchConfig.effectiveConfigSnapshot();
    }

    public static JsonObject promptPrior(
            GameController controller,
            AIPlayer bot,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        JsonObject prior = new JsonObject();
        prior.addProperty("schema", "monopoly-deal-search-prior-v1");
        prior.addProperty("source", "lookahead");
        prior.addProperty("available", false);
        if (controller == null || bot == null || candidates == null || candidates.isEmpty()) {
            prior.addProperty("reason", "missing_controller_bot_or_candidates");
            return prior;
        }

        HardAiPlayStrategy priorFallback = new HardAiPlayStrategy();
        List<ScoredCandidate> scoredCandidates = new ArrayList<>();
        ScoredCandidate best = null;
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            double score = RolloutSimulator.evaluateCandidate(controller, bot, candidate, priorFallback);
            ScoredCandidate scored = new ScoredCandidate(candidate, score);
            scoredCandidates.add(scored);
            if (best == null || scored.score() > best.score()) {
                best = scored;
            }
        }
        if (best == null || best.score() <= INVALID_SCORE / 2d) {
            prior.addProperty("reason", "no_valid_search_score");
            return prior;
        }

        scoredCandidates.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());
        JsonObject scores = new JsonObject();
        JsonObject ranks = new JsonObject();
        JsonObject adjustments = new JsonObject();
        for (int i = 0; i < scoredCandidates.size(); i++) {
            ScoredCandidate scored = scoredCandidates.get(i);
            String id = scored.candidate().id();
            scores.addProperty(id, scored.score());
            ranks.addProperty(id, i + 1);
            adjustments.addProperty(id, BoardEvaluator.candidateAdjustment(bot, scored.candidate()));
        }
        prior.addProperty("available", true);
        prior.addProperty("bestChoiceId", best.candidate().id());
        prior.addProperty("bestScore", best.score());
        prior.addProperty("guidance",
                "This is the current champion one-ply search score after simulating each legal play. "
                        + "Treat it as stronger evidence than localScore unless the strategic text reveals "
                        + "a clear immediate win/block reason.");
        prior.add("candidateScores", scores);
        prior.add("candidateRanks", ranks);
        prior.add("candidateAdjustments", adjustments);
        return prior;
    }

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        if (!(bridge instanceof GameController controller)) {
            return fallback.tryPlayOneCard(bot, context, bridge);
        }
        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, context);
        if (candidates.isEmpty()) {
            return false;
        }

        PlayActionRequest hardRequest = RolloutSimulator.hardRequest(bot, context, fallback);
        candidates = CandidatePruner.prune(candidates, hardRequest);

        List<ScoredCandidate> scoredCandidates = new ArrayList<>();
        ScoredCandidate best = null;
        ScoredCandidate immediateBest = null;
        ScoredCandidate immediateBestRolloutScore = null;
        boolean needsImmediateBest = SearchConfig.ROLLOUT_REMAINING_TURN && SearchConfig.ROLLOUT_OVERRIDE_MARGIN > 0d;
        ScoredCandidate hardChoice = null;
        String hardKey = BoardEvaluator.requestKey(hardRequest);
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            double score = RolloutSimulator.evaluateCandidate(controller, bot, candidate, fallback);
            ScoredCandidate scored = new ScoredCandidate(candidate, score);
            scoredCandidates.add(scored);
            if (needsImmediateBest) {
                double immediateScore = RolloutSimulator.evaluateImmediate(controller, bot.getPlayerId(), candidate)
                        + DeepSeekAiPlayStrategy.candidateScore(candidate) * 0.03d;
                ScoredCandidate immediateScored = new ScoredCandidate(candidate, immediateScore);
                if (immediateBest == null || immediateScored.score() > immediateBest.score()) {
                    immediateBest = immediateScored;
                    immediateBestRolloutScore = scored;
                }
            }
            if (!hardKey.isBlank() && hardKey.equals(BoardEvaluator.requestKey(candidate.request()))) {
                hardChoice = scored;
            }
            if (best == null || scored.score() > best.score()) {
                best = scored;
            }
        }
        if (best == null || best.score() <= INVALID_SCORE / 2d) {
            return fallback.tryPlayOneCard(bot, context, bridge);
        }

        JsonObject traceContext = DecisionTraceRecorder.promptContext(bot, context, candidates);
        ScoredCandidate rawRolloutBest = best;
        ScoredCandidate rolloutImmediateBest = immediateBestRolloutScore;
        best = chooseWithRolloutOverrideMargin(best, rolloutImmediateBest);
        ScoredCandidate rolloutGateChoice = best;
        ScoredCandidate marginChoice = RolloutSimulator.chooseWithHardMargin(best, hardChoice);
        marginChoice = chooseWithSameEffectHardTargetMargin(best, hardChoice, marginChoice);
        marginChoice = chooseWithHardRentFallbackMargin(best, hardChoice, marginChoice);
        CorrectedChoice corrected = chooseWithCorrector(
                context,
                bot,
                traceContext,
                scoredCandidates,
                best,
                hardChoice,
                marginChoice,
                rawRolloutBest,
                rolloutImmediateBest,
                rolloutGateChoice);
        ScoredCandidate chosen = corrected.chosen();
        JsonObject recordedTraceContext = DecisionTraceRecorder.contextWithSourcePolicy(
                bot,
                traceContext,
                scoredCandidates,
                best,
                hardChoice,
                chosen,
                rawRolloutBest,
                rolloutImmediateBest,
                rolloutGateChoice);
        recordedTraceContext = DecisionTraceRecorder.withCounterfactualMemento(controller, recordedTraceContext);
        long traceStateSequence = context == null ? 0L : context.getStateSequence();
        try {
            bridge.submitPlayAction(chosen.candidate().request());
            DecisionTraceRecorder.recordPlayTrace(
                    traceSink, traceSessionId,
                    bot,
                    traceStateSequence,
                    recordedTraceContext,
                    candidates,
                    scoredCandidates,
                    best,
                    hardChoice,
                    chosen,
                    corrected.metadata(),
                    rawRolloutBest,
                    rolloutImmediateBest,
                    rolloutGateChoice);
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
        AiHeuristics.AiResponseDecision chosen = fallbackDecision;
        if (SearchConfig.RESPONSE_TENANT_THRESHOLD == 3 && SearchConfig.RESPONSE_COUNTER_THRESHOLD == 5) {
            DecisionTraceRecorder.recordResponseTrace(traceSink, traceSessionId, bot, context, counterRole, fallbackDecision, chosen);
            return chosen;
        }
        chosen = chooseResponseWithThresholds(
                bot,
                context,
                counterRole,
                SearchConfig.RESPONSE_TENANT_THRESHOLD,
                SearchConfig.RESPONSE_COUNTER_THRESHOLD);
        DecisionTraceRecorder.recordResponseTrace(traceSink, traceSessionId, bot, context, counterRole, fallbackDecision, chosen);
        return chosen;
    }

    @Override
    public PaymentSettlement.PaymentChoice choosePayment(
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        return PaymentAdvisor.choosePayment(traceSink, traceSessionId, bot, context, creditor, amountDue, fallbackChoice);
    }

    static JsonObject auxiliaryContextWithMemento(String prompt, GameContext context) {
        return DecisionTraceRecorder.auxiliaryContextWithMemento(prompt, context);
    }

    static boolean defaultBoardAwarePaymentForTest() {
        return SearchConfig.BOARD_AWARE_PAYMENT;
    }

    static PaymentSettlement.PaymentChoice boardAwarePaymentChoiceForTest(
            Player debtor,
            int amountDue) {
        return PaymentAdvisor.boardAwarePaymentChoice(debtor, amountDue);
    }

    static double paymentBoardDamageForTest(Player debtor, List<Card> cards) {
        return PaymentAdvisor.paymentBoardDamage(debtor, cards);
    }

    @Override
    public List<Card> chooseOverflowDiscards(
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards) {
        return OverflowDiscardAdvisor.chooseOverflowDiscards(traceSink, traceSessionId, bot, context, limit, fallbackCards);
    }

    private ScoredCandidate chooseWithRolloutOverrideMargin(
            ScoredCandidate best,
            ScoredCandidate immediateBest) {
        if (SearchConfig.ROLLOUT_OVERRIDE_MARGIN <= 0d
                || !SearchConfig.ROLLOUT_REMAINING_TURN
                || best == null
                || best.candidate() == null
                || immediateBest == null
                || immediateBest.candidate() == null
                || best.candidate().id().equals(immediateBest.candidate().id())) {
            return best;
        }
        if (!rolloutOverrideEffectAllowed(best.candidate())) {
            return immediateBest;
        }
        if (!rolloutOverrideTransitionAllowed(immediateBest.candidate(), best.candidate())) {
            return immediateBest;
        }
        return best.score() - immediateBest.score() >= SearchConfig.ROLLOUT_OVERRIDE_MARGIN
                ? best
                : immediateBest;
    }

    static boolean rolloutOverrideEffectAllowed(AiHeuristics.AiPlayCandidate candidate) {
        return SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_EFFECTS.isEmpty()
                || SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_EFFECTS.contains(BoardEvaluator.candidateEffect(null, candidate));
    }

    static boolean rolloutOverrideTransitionAllowed(
            AiHeuristics.AiPlayCandidate immediateBest,
            AiHeuristics.AiPlayCandidate rolloutBest) {
        if (SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS.isEmpty()) {
            return true;
        }
        String transition = BoardEvaluator.candidateEffect(null, immediateBest)
                + "->"
                + BoardEvaluator.candidateEffect(null, rolloutBest);
        return SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS.contains(transition.toUpperCase(Locale.ROOT));
    }

    private ScoredCandidate chooseWithSameEffectHardTargetMargin(
            ScoredCandidate best,
            ScoredCandidate hardChoice,
            ScoredCandidate currentChoice) {
        if (SearchConfig.SAME_EFFECT_HARD_TARGET_MARGIN < 0d
                || best == null
                || hardChoice == null
                || best.candidate() == null
                || hardChoice.candidate() == null) {
            return currentChoice;
        }
        if (!sameEffectDifferentTarget(best.candidate(), hardChoice.candidate())) {
            return currentChoice;
        }
        return best.score() - hardChoice.score() >= SearchConfig.SAME_EFFECT_HARD_TARGET_MARGIN
                ? currentChoice
                : hardChoice;
    }

    private ScoredCandidate chooseWithHardRentFallbackMargin(
            ScoredCandidate best,
            ScoredCandidate hardChoice,
            ScoredCandidate currentChoice) {
        if (SearchConfig.HARD_RENT_FALLBACK_MARGIN < 0d
                || best == null
                || hardChoice == null
                || best.candidate() == null
                || hardChoice.candidate() == null) {
            return currentChoice;
        }
        String hardEffect = BoardEvaluator.candidateEffect(null, hardChoice.candidate());
        if (!"RENT".equals(hardEffect) && !"RENT_DUAL".equals(hardEffect)) {
            return currentChoice;
        }
        String bestEffect = BoardEvaluator.candidateEffect(null, best.candidate());
        if (!"DEPLOY".equals(bestEffect) && !"PASS_GO".equals(bestEffect)) {
            return currentChoice;
        }
        return best.score() - hardChoice.score() >= SearchConfig.HARD_RENT_FALLBACK_MARGIN
                ? currentChoice
                : hardChoice;
    }

    private CorrectedChoice chooseWithCorrector(
            GameContext context,
            AIPlayer bot,
            JsonObject baseContext,
            List<ScoredCandidate> scoredCandidates,
            ScoredCandidate modelBest,
            ScoredCandidate hardChoice,
            ScoredCandidate currentChoice,
            ScoredCandidate rawRolloutBest,
            ScoredCandidate rolloutImmediateBest,
            ScoredCandidate rolloutGateChoice) {
        JsonObject metadata = new JsonObject();
        if (correctorModel == null || currentChoice == null || scoredCandidates == null || scoredCandidates.isEmpty()) {
            return new CorrectedChoice(currentChoice, metadata);
        }
        JsonObject scoringContext = contextWithSourcePolicy(
                bot,
                baseContext,
                scoredCandidates,
                modelBest,
                hardChoice,
                currentChoice,
                rawRolloutBest,
                rolloutImmediateBest,
                rolloutGateChoice);

        List<ScoredCandidate> eligibleCandidates = correctorEligibleCandidates(scoredCandidates, currentChoice);
        metadata.addProperty("modelPath", correctorModelPath);
        metadata.addProperty("minScoreGap", SearchConfig.CORRECTOR_MIN_SCORE_GAP);
        metadata.addProperty("maxLookaheadGap", SearchConfig.CORRECTOR_MAX_LOOKAHEAD_GAP);
        JsonArray allowedEffects = new JsonArray();
        for (String effect : SearchConfig.CORRECTOR_ALLOWED_EFFECTS) {
            allowedEffects.add(effect);
        }
        metadata.add("allowedEffects", allowedEffects);
        JsonArray allowedActionTypes = new JsonArray();
        for (String actionType : SearchConfig.CORRECTOR_ALLOWED_ACTION_TYPES) {
            allowedActionTypes.add(actionType);
        }
        metadata.add("allowedActionTypes", allowedActionTypes);
        metadata.addProperty("eligibleCandidateCount", eligibleCandidates.size());
        if (eligibleCandidates.size() <= 1) {
            metadata.addProperty("overrideUsed", false);
            metadata.addProperty("skipReason", "no_eligible_tactical_candidate");
            return new CorrectedChoice(currentChoice, metadata);
        }

        ScoredCandidate correctorBest = null;
        double correctorBestScore = Double.NEGATIVE_INFINITY;
        double currentCorrectorScore = Double.NEGATIVE_INFINITY;
        JsonObject correctorScores = new JsonObject();
        try {
            for (ScoredCandidate scored : eligibleCandidates) {
                if (scored == null || scored.score() <= INVALID_SCORE / 2d) {
                    continue;
                }
                JsonObject payload = DecisionTraceRecorder.GSON.toJsonTree(scored.candidate().request()).getAsJsonObject();
                double rankerScore = correctorModel.score(
                        LocalRankerAiPlayStrategy.FeatureExtractor.featuresFor(
                                "PLAY_CARD",
                                context,
                                scoringContext,
                                payload,
                                scored.candidate().id(),
                                scored.candidate().summary()));
                correctorScores.addProperty(scored.candidate().id(), rankerScore);
                if (scored.candidate().id().equals(currentChoice.candidate().id())) {
                    currentCorrectorScore = rankerScore;
                }
                if (correctorBest == null || rankerScore > correctorBestScore) {
                    correctorBest = scored;
                    correctorBestScore = rankerScore;
                }
            }
        } catch (RuntimeException ex) {
            metadata.addProperty("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return new CorrectedChoice(currentChoice, metadata);
        }

        metadata.addProperty("currentChoiceId", currentChoice.candidate().id());
        metadata.addProperty("currentCorrectorScore", currentCorrectorScore);
        metadata.add("candidateScores", correctorScores);
        if (correctorBest == null || correctorBest.candidate().id().equals(currentChoice.candidate().id())) {
            metadata.addProperty("overrideUsed", false);
            return new CorrectedChoice(currentChoice, metadata);
        }

        double correctorGap = correctorBestScore - currentCorrectorScore;
        double lookaheadGap = currentChoice.score() - correctorBest.score();
        boolean override = correctorGap >= SearchConfig.CORRECTOR_MIN_SCORE_GAP
                && lookaheadGap <= SearchConfig.CORRECTOR_MAX_LOOKAHEAD_GAP
                && (SearchConfig.CORRECTOR_ALLOW_LOWER_LOOKAHEAD_SCORE || lookaheadGap <= 0d);
        metadata.addProperty("bestChoiceId", correctorBest.candidate().id());
        metadata.addProperty("bestCorrectorScore", correctorBestScore);
        metadata.addProperty("correctorGap", correctorGap);
        metadata.addProperty("lookaheadGap", lookaheadGap);
        metadata.addProperty("overrideUsed", override);
        return new CorrectedChoice(override ? correctorBest : currentChoice, metadata);
    }

    private static JsonObject contextWithSourcePolicy(
            AIPlayer bot,
            JsonObject baseContext,
            List<ScoredCandidate> scoredCandidates,
            ScoredCandidate modelBest,
            ScoredCandidate hardChoice,
            ScoredCandidate currentChoice,
            ScoredCandidate rawRolloutBest,
            ScoredCandidate rolloutImmediateBest,
            ScoredCandidate rolloutGateChoice) {
        JsonObject out = baseContext == null ? new JsonObject() : baseContext.deepCopy();
        JsonObject sourcePolicy = new JsonObject();
        if (currentChoice != null && currentChoice.candidate() != null) {
            sourcePolicy.addProperty("choiceId", currentChoice.candidate().id());
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("hardMargin", SearchConfig.HARD_MARGIN);
        metadata.addProperty("sameEffectHardTargetMargin", SearchConfig.SAME_EFFECT_HARD_TARGET_MARGIN);
        metadata.addProperty("hardRentFallbackMargin", SearchConfig.HARD_RENT_FALLBACK_MARGIN);
        metadata.addProperty("rolloutRemainingTurn", SearchConfig.ROLLOUT_REMAINING_TURN);
        metadata.addProperty("rolloutOverrideMargin", SearchConfig.ROLLOUT_OVERRIDE_MARGIN);
        metadata.addProperty("rolloutOverrideAllowedEffects", String.join(",", SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_EFFECTS));
        metadata.addProperty("rolloutPolicy", SearchConfig.ROLLOUT_POLICY);
        DecisionTraceRecorder.addRolloutGateMetadata(metadata, rawRolloutBest, rolloutImmediateBest, rolloutGateChoice);
        if (modelBest != null && modelBest.candidate() != null) {
            metadata.addProperty("modelBestId", modelBest.candidate().id());
            metadata.addProperty("modelBestScore", modelBest.score());
        }
        if (hardChoice != null && hardChoice.candidate() != null) {
            metadata.addProperty("hardChoiceId", hardChoice.candidate().id());
            metadata.addProperty("hardChoiceScore", hardChoice.score());
            metadata.addProperty("hardFallbackUsed",
                    currentChoice != null
                            && currentChoice.candidate() != null
                            && currentChoice.candidate().id().equals(hardChoice.candidate().id())
                            && modelBest != null
                            && modelBest.candidate() != null
                            && !modelBest.candidate().id().equals(hardChoice.candidate().id()));
            metadata.addProperty("sameEffectHardTargetFallbackUsed",
                    DecisionTraceRecorder.sameEffectHardTargetFallbackUsed(modelBest, hardChoice, currentChoice));
            metadata.addProperty("hardRentFallbackUsed",
                    DecisionTraceRecorder.hardRentFallbackUsed(modelBest, hardChoice, currentChoice));
        }
        JsonObject scores = new JsonObject();
        JsonObject adjustments = new JsonObject();
        if (scoredCandidates != null) {
            for (ScoredCandidate scored : scoredCandidates) {
                if (scored == null || scored.candidate() == null) {
                    continue;
                }
                scores.addProperty(scored.candidate().id(), scored.score());
                adjustments.addProperty(scored.candidate().id(), BoardEvaluator.candidateAdjustment(bot, scored.candidate()));
            }
        }
        metadata.add("candidateScores", scores);
        metadata.add("candidateAdjustments", adjustments);
        sourcePolicy.add("metadata", metadata);
        out.add("sourcePolicy", sourcePolicy);
        return out;
    }

    private static List<ScoredCandidate> correctorEligibleCandidates(
            List<ScoredCandidate> scoredCandidates,
            ScoredCandidate currentChoice) {
        List<ScoredCandidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (currentChoice != null && currentChoice.candidate() != null) {
            out.add(currentChoice);
            seen.add(currentChoice.candidate().id());
        }
        if (scoredCandidates == null) {
            return out;
        }
        for (ScoredCandidate scored : scoredCandidates) {
            if (scored == null || scored.candidate() == null) {
                continue;
            }
            if (seen.contains(scored.candidate().id())) {
                continue;
            }
            if (CandidatePruner.isCorrectorTacticalCandidate(scored.candidate())) {
                out.add(scored);
                seen.add(scored.candidate().id());
            }
        }
        return out;
    }

    static String naturalWinnerId(GameStateSnapshot snapshot) {
        return BoardEvaluator.naturalWinnerId(snapshot);
    }

    static double defaultBuildingActionBonusForTest() {
        return SearchConfig.BUILDING_ACTION_BONUS;
    }

    static double defaultBuildingRentBonusValueForTest() {
        return SearchConfig.BUILDING_RENT_BONUS_VALUE;
    }

    static double defaultOpponentBuildingThreatValueForTest() {
        return SearchConfig.OPPONENT_BUILDING_THREAT_VALUE;
    }

    static double defaultPassGoDepositBonusForTest() {
        return SearchConfig.PASS_GO_DEPOSIT_BONUS;
    }

    static double defaultSameEffectHardTargetMarginForTest() {
        return SearchConfig.SAME_EFFECT_HARD_TARGET_MARGIN;
    }

    static double defaultHardRentFallbackMarginForTest() {
        return SearchConfig.HARD_RENT_FALLBACK_MARGIN;
    }

    static double defaultRolloutOverrideMarginForTest() {
        return SearchConfig.ROLLOUT_OVERRIDE_MARGIN;
    }

    static int defaultRolloutOverrideAllowedEffectsForTest() {
        return SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_EFFECTS.size();
    }

    static int defaultRolloutOverrideAllowedTransitionsForTest() {
        return SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS.size();
    }

    static boolean rolloutOverrideTransitionAllowedForTest(
            AiHeuristics.AiPlayCandidate immediateBest,
            AiHeuristics.AiPlayCandidate rolloutBest) {
        return rolloutOverrideTransitionAllowed(immediateBest, rolloutBest);
    }

    static double defaultStealCompletionGainMultiplierForTest() {
        return SearchConfig.STEAL_COMPLETION_GAIN_MULTIPLIER;
    }

    static int defaultStructuredTacticalReservedCandidatesForTest() {
        return SearchConfig.STRUCTURED_TACTICAL_RESERVED_CANDIDATES;
    }

    static double defaultStructuredPrunePriorityWeightForTest() {
        return SearchConfig.STRUCTURED_PRUNE_PRIORITY_WEIGHT;
    }

    static boolean defaultBoardAwareOverflowDiscardForTest() {
        return SearchConfig.BOARD_AWARE_OVERFLOW_DISCARD;
    }

    static double defaultWildOverfullSetPenaltyForTest() {
        return SearchConfig.WILD_OVERFULL_SET_PENALTY;
    }

    static int defaultResponseTenantThresholdForTest() {
        return SearchConfig.RESPONSE_TENANT_THRESHOLD;
    }

    static int defaultResponseCounterThresholdForTest() {
        return SearchConfig.RESPONSE_COUNTER_THRESHOLD;
    }

    static double candidateAdjustmentForTest(AiHeuristics.AiPlayCandidate candidate) {
        return BoardEvaluator.candidateAdjustment(null, candidate);
    }

    static String candidateEffectForTest(AiHeuristics.AiPlayCandidate candidate) {
        return BoardEvaluator.candidateEffect(null, candidate);
    }

    static double wildDeployAdjustmentForTest(
            Player actor,
            PlayActionRequest request,
            double wildDeployPenalty,
            double wildShortSetPenalty,
            double wildCompletionPenalty,
            double wildOverfullSetPenalty) {
        return BoardEvaluator.wildDeployAdjustment(
                actor,
                request,
                wildDeployPenalty,
                wildShortSetPenalty,
                wildCompletionPenalty,
                wildOverfullSetPenalty);
    }

    static double overflowDiscardRetentionScoreForTest(AIPlayer bot, Card card) {
        return OverflowDiscardAdvisor.overflowDiscardRetentionScore(bot, card);
    }

    static AiHeuristics.AiResponseDecision responseDecisionForThresholdsForTest(
            AIPlayer bot,
            GameContext context,
            boolean counterRole,
            int tenantThreshold,
            int counterThreshold) {
        return chooseResponseWithThresholds(bot, context, counterRole, tenantThreshold, counterThreshold);
    }

    static boolean sameEffectDifferentTargetForTest(
            AiHeuristics.AiPlayCandidate a,
            AiHeuristics.AiPlayCandidate b) {
        return sameEffectDifferentTarget(a, b);
    }

    static boolean sameEffectDifferentTarget(
            AiHeuristics.AiPlayCandidate a,
            AiHeuristics.AiPlayCandidate b) {
        if (a == null || b == null || a.request() == null || b.request() == null) {
            return false;
        }
        String effect = BoardEvaluator.candidateEffect(null, a);
        if (effect.isBlank()
                || !effect.equals(BoardEvaluator.candidateEffect(null, b))
                || !SearchConfig.SAME_EFFECT_HARD_TARGET_EFFECTS.contains(effect)) {
            return false;
        }
        PlayActionRequest left = a.request();
        PlayActionRequest right = b.request();
        if (!SearchConfig.trim(left.getActionType()).equalsIgnoreCase(SearchConfig.trim(right.getActionType()))
                || !SearchConfig.trim(left.getCardId()).equals(SearchConfig.trim(right.getCardId()))) {
            return false;
        }
        boolean targetDiffers = !SearchConfig.trim(left.getTargetPlayerId()).equals(SearchConfig.trim(right.getTargetPlayerId()))
                || !SearchConfig.trim(left.getTargetColorKey()).equalsIgnoreCase(SearchConfig.trim(right.getTargetColorKey()))
                || !SearchConfig.trim(left.getTargetCardId()).equals(SearchConfig.trim(right.getTargetCardId()))
                || !SearchConfig.trim(left.getActorCardId()).equals(SearchConfig.trim(right.getActorCardId()))
                || !SearchConfig.trim(left.getTargetZone()).equalsIgnoreCase(SearchConfig.trim(right.getTargetZone()));
        return targetDiffers;
    }

    private static AiHeuristics.AiResponseDecision chooseResponseWithThresholds(
            AIPlayer bot,
            GameContext context,
            boolean counterRole,
            int tenantThreshold,
            int counterThreshold) {
        if (bot == null || context == null || context.getResponseState() == null) {
            return AiHeuristics.AiResponseDecision.pass();
        }
        ActionCard waiver = firstJustSayNo(bot);
        if (waiver == null) {
            return AiHeuristics.AiResponseDecision.unavailable("no Just Say No in hand");
        }
        int impact = estimateResponseImpact(bot, context, counterRole);
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType("ACTION");
        req.setCardId(waiver.getId());
        req.setActingPlayerId(bot.getPlayerId());
        int threshold = counterRole ? counterThreshold : tenantThreshold;
        if (impact < threshold) {
            return AiHeuristics.AiResponseDecision.hold(
                    req,
                    "Hold Just Say No, estimated impact " + impact + "M.");
        }
        return AiHeuristics.AiResponseDecision.play(
                req,
                "Protect/counter high-impact effect, estimated impact " + impact + "M.");
    }

    private static ActionCard firstJustSayNo(AIPlayer bot) {
        if (bot == null) {
            return null;
        }
        for (Card card : bot.getHandCardsView()) {
            if (card instanceof ActionCard action
                    && "RENT_WAIVER".equalsIgnoreCase(SearchConfig.trim(action.getEffectCode()))) {
                return action;
            }
        }
        return null;
    }

    private static int estimateResponseImpact(AIPlayer bot, GameContext context, boolean counterRole) {
        int best = 0;
        for (com.monopoly.model.effects.EffectStackEntry entry : context.getEffectStackView()) {
            if (entry == null) {
                continue;
            }
            if (entry.isRentLike()) {
                String relevant = counterRole ? entry.getActorPlayerId() : entry.getTenantPlayerId();
                if (bot.getPlayerId().equals(relevant)) {
                    best = Math.max(best, entry.getAmountDue());
                }
            } else if (entry.isActionLike()) {
                String relevant = counterRole ? entry.getActorPlayerId() : entry.getTenantPlayerId();
                if (bot.getPlayerId().equals(relevant)) {
                    best = Math.max(best, actionResponseImpact(entry));
                }
            }
        }
        return best;
    }

    private static int actionResponseImpact(com.monopoly.model.effects.EffectStackEntry entry) {
        String effect = entry == null || entry.getActionEffectCode() == null
                ? "" : entry.getActionEffectCode().trim().toUpperCase(Locale.ROOT);
        return switch (effect) {
            case "DEAL_BREAKER" -> 9;
            case "STEAL_PROPERTY", "FORCED_DEAL" -> 7;
            default -> 5;
        };
    }

    record ScoredCandidate(
            AiHeuristics.AiPlayCandidate candidate,
            double score) {
    }

    private record CorrectedChoice(
            ScoredCandidate chosen,
            JsonObject metadata) {
    }
}