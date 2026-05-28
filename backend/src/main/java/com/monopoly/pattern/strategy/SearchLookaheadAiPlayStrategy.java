package com.monopoly.pattern.strategy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.PropertyColorProgress;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.simulation.DecisionTraceSink;
import com.monopoly.simulation.SimulationDecisionCandidate;
import com.monopoly.simulation.SimulationDecisionRequest;
import com.monopoly.simulation.SimulationDecisionResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local lookahead policy: play candidates are executed once in an isolated
 * cloned session, then ranked by resulting board pressure.
 * <p>
 * The default is intentionally one-ply. Earlier multi-play rollout variants
 * were stronger on small samples but could use freshly drawn hidden cards
 * during simulation, which made the evidence unreliable.
 */
public final class SearchLookaheadAiPlayStrategy implements AiPlayStrategy, AiChoiceAdvisor {

    private static final Gson GSON = new Gson();
    private static final int MAX_CANDIDATES =
            Integer.getInteger("monopoly.search.maxCandidates", 16);
    private static final int TACTICAL_RESERVED_CANDIDATES =
            Integer.getInteger("monopoly.search.tacticalReservedCandidates", 0);
    private static final int STRUCTURED_TACTICAL_RESERVED_CANDIDATES =
            Integer.getInteger("monopoly.search.structuredTacticalReservedCandidates", 0);
    private static final double STRUCTURED_PRUNE_PRIORITY_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.structuredPrunePriorityWeight", "0"));
    private static final boolean ROLLOUT_REMAINING_TURN =
            Boolean.parseBoolean(System.getProperty("monopoly.search.rolloutRemainingTurn", "false"));
    private static final double ROLLOUT_OVERRIDE_MARGIN =
            Double.parseDouble(System.getProperty("monopoly.search.rolloutOverrideMargin", "0"));
    private static final Set<String> ROLLOUT_OVERRIDE_ALLOWED_EFFECTS = parseEffectSet(
            System.getProperty("monopoly.search.rolloutOverrideAllowedEffects", ""));
    private static final Set<String> ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS = parseEffectSet(
            System.getProperty("monopoly.search.rolloutOverrideAllowedTransitions", ""));
    private static final String ROLLOUT_POLICY =
            System.getProperty("monopoly.search.rolloutPolicy", "hard")
                    .trim()
                    .toLowerCase(Locale.ROOT);
    private static final int MAX_ROLLOUT_CANDIDATES =
            Integer.getInteger("monopoly.search.rolloutMaxCandidates", 8);
    private static final boolean ROLLOUT_NEXT_OPPONENT_TURN =
            Boolean.parseBoolean(System.getProperty("monopoly.search.rolloutNextOpponentTurn", "false"));
    private static final double HARD_MARGIN =
            Double.parseDouble(System.getProperty("monopoly.search.hardMargin", "0"));
    private static final double MAX_OPPONENT_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.maxOpponentWeight", "0.92"));
    private static final double AVG_OPPONENT_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.avgOpponentWeight", "0.12"));
    private static final double THREAT_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.threatWeight", "1.0"));
    private static final double COMPLETE_SET_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.completeSetValue", "13000"));
    private static final double NEAR_COMPLETE_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.nearCompleteValue", "1000"));
    private static final double CAPPED_PROGRESS_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.cappedProgressValue", "680"));
    private static final double PROPERTY_COUNT_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.propertyCountValue", "120"));
    private static final double BANK_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.bankValue", "35"));
    private static final double BUILDING_RENT_BONUS_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.buildingRentBonusValue", "320"));
    private static final double OPPONENT_BUILDING_THREAT_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.opponentBuildingThreatValue", "260"));
    private static final double CONDITIONAL_THREAT_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.conditionalThreatWeight", "0"));
    private static final int CONDITIONAL_THREAT_SELF_MAX_SETS =
            Integer.getInteger("monopoly.search.conditionalThreatSelfMaxSets", 1);
    private static final int CONDITIONAL_THREAT_OPPONENT_MIN_SETS =
            Integer.getInteger("monopoly.search.conditionalThreatOpponentMinSets", 2);
    private static final int CONDITIONAL_THREAT_OPPONENT_MIN_ALMOST =
            Integer.getInteger("monopoly.search.conditionalThreatOpponentMinAlmost", 2);
    private static final double PASS_GO_EXPECTED_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.passGoExpectedValue", "4000"));
    private static final double PASS_GO_OVERFLOW_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.passGoOverflowPenalty", "180"));
    private static final double PASS_GO_DEPOSIT_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.passGoDepositBonus", "0"));
    private static final double LOW_BIRTHDAY_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.lowBirthdayPenalty", "0"));
    private static final double BIRTHDAY_EXPECTED_PAID_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.birthdayExpectedPaidMultiplier", "0"));
    private static final double RENT_EXPECTED_PAID_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.rentExpectedPaidMultiplier", "600"));
    private static final double DEBT_EXPECTED_PAID_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.debtExpectedPaidMultiplier", "300"));
    private static final double STEAL_PROPERTY_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.stealPropertyBonus", "0"));
    private static final double STEAL_COMPLETION_GAIN_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.stealCompletionGainMultiplier", "0"));
    private static final double STEAL_OPP_COMPLETION_LOSS_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.stealOppCompletionLossMultiplier", "0"));
    private static final double STEAL_TAKE_VALUE_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.stealTakeValueMultiplier", "0"));
    private static final double STEAL_WILD_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.stealWildBonus", "0"));
    private static final double FORCED_DEAL_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.forcedDealBonus", "0"));
    private static final double BUILDING_ACTION_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.buildingActionBonus", "900"));
    private static final double RENT_ACTION_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.rentActionBonus", "0"));
    private static final double WILD_DEPLOY_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.wildDeployPenalty", "0"));
    private static final double WILD_SHORT_SET_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.wildShortSetPenalty", "0"));
    private static final double WILD_COMPLETION_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.wildCompletionPenalty", "0"));
    private static final double WILD_OVERFULL_SET_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.wildOverfullSetPenalty", "0"));
    private static final boolean BOARD_AWARE_OVERFLOW_DISCARD =
            Boolean.parseBoolean(System.getProperty("monopoly.search.boardAwareOverflowDiscard", "true"));
    private static final boolean BOARD_AWARE_PAYMENT =
            Boolean.parseBoolean(System.getProperty("monopoly.search.boardAwarePayment", "true"));
    private static final double PAYMENT_COMPLETE_SET_BREAK_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.paymentCompleteSetBreakPenalty", "4"));
    private static final double PAYMENT_NEAR_SET_BREAK_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.paymentNearSetBreakPenalty", "1.5"));
    private static final int MAX_PAYMENT_CANDIDATES =
            Integer.getInteger("monopoly.search.maxPaymentCandidates", 512);
    private static final int RESPONSE_TENANT_THRESHOLD =
            Integer.getInteger("monopoly.search.responseTenantThreshold", 3);
    private static final int RESPONSE_COUNTER_THRESHOLD =
            Integer.getInteger("monopoly.search.responseCounterThreshold", 5);
    private static final double SAME_EFFECT_HARD_TARGET_MARGIN =
            Double.parseDouble(System.getProperty("monopoly.search.sameEffectHardTargetMargin", "-1"));
    private static final double HARD_RENT_FALLBACK_MARGIN =
            Double.parseDouble(System.getProperty("monopoly.search.hardRentFallbackMargin", "-1"));
    private static final Set<String> SAME_EFFECT_HARD_TARGET_EFFECTS = parseEffectSet(
            System.getProperty(
                    "monopoly.search.sameEffectHardTargetEffects",
                    "STEAL_PROPERTY,FORCED_DEAL"));
    private static final String CORRECTOR_MODEL_PATH =
            System.getProperty("monopoly.search.correctorModelPath", "").trim();
    private static final double CORRECTOR_MIN_SCORE_GAP =
            Double.parseDouble(System.getProperty("monopoly.search.correctorMinScoreGap", "0.6"));
    private static final double CORRECTOR_MAX_LOOKAHEAD_GAP =
            Double.parseDouble(System.getProperty("monopoly.search.correctorMaxLookaheadGap", "900"));
    private static final boolean CORRECTOR_ALLOW_LOWER_LOOKAHEAD_SCORE =
            Boolean.parseBoolean(System.getProperty(
                    "monopoly.search.correctorAllowLowerLookaheadScore",
                    "false"));
    private static final boolean TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO =
            Boolean.parseBoolean(System.getProperty(
                    "monopoly.search.trace.includeMemento",
                    "false"));
    private static final Set<String> CORRECTOR_ALLOWED_EFFECTS = parseEffectSet(
            System.getProperty(
                    "monopoly.search.correctorAllowedEffects",
                    "RENT,RENT_DUAL,HOUSE,HOTEL,DEBT_COLLECTOR,STEAL_PROPERTY,FORCED_DEAL,DEAL_BREAKER,PASS_GO"));
    private static final Set<String> CORRECTOR_ALLOWED_ACTION_TYPES = parseEffectSet(
            System.getProperty("monopoly.search.correctorAllowedActionTypes", "ACTION"));
    private static final double INVALID_SCORE = -1_000_000_000d;
    private static final AtomicLong TRACE_SEQUENCE = new AtomicLong();

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
        this.correctorModelPath = CORRECTOR_MODEL_PATH;
        this.correctorModel = correctorModelPath.isBlank()
                ? null
                : LocalRankerAiPlayStrategy.RankerModel.load(Path.of(correctorModelPath));
    }

    public static JsonObject effectiveConfigSnapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("monopoly.search.maxCandidates", MAX_CANDIDATES);
        out.addProperty("monopoly.search.tacticalReservedCandidates", TACTICAL_RESERVED_CANDIDATES);
        out.addProperty("monopoly.search.structuredTacticalReservedCandidates", STRUCTURED_TACTICAL_RESERVED_CANDIDATES);
        out.addProperty("monopoly.search.structuredPrunePriorityWeight", STRUCTURED_PRUNE_PRIORITY_WEIGHT);
        out.addProperty("monopoly.search.rolloutRemainingTurn", ROLLOUT_REMAINING_TURN);
        out.addProperty("monopoly.search.rolloutOverrideMargin", ROLLOUT_OVERRIDE_MARGIN);
        out.addProperty("monopoly.search.rolloutOverrideAllowedEffects", effectSetString(ROLLOUT_OVERRIDE_ALLOWED_EFFECTS));
        out.addProperty("monopoly.search.rolloutOverrideAllowedTransitions", effectSetString(ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS));
        out.addProperty("monopoly.search.rolloutPolicy", ROLLOUT_POLICY);
        out.addProperty("monopoly.search.rolloutMaxCandidates", MAX_ROLLOUT_CANDIDATES);
        out.addProperty("monopoly.search.rolloutNextOpponentTurn", ROLLOUT_NEXT_OPPONENT_TURN);
        out.addProperty("monopoly.search.hardMargin", HARD_MARGIN);
        out.addProperty("monopoly.search.maxOpponentWeight", MAX_OPPONENT_WEIGHT);
        out.addProperty("monopoly.search.avgOpponentWeight", AVG_OPPONENT_WEIGHT);
        out.addProperty("monopoly.search.threatWeight", THREAT_WEIGHT);
        out.addProperty("monopoly.search.completeSetValue", COMPLETE_SET_VALUE);
        out.addProperty("monopoly.search.nearCompleteValue", NEAR_COMPLETE_VALUE);
        out.addProperty("monopoly.search.cappedProgressValue", CAPPED_PROGRESS_VALUE);
        out.addProperty("monopoly.search.propertyCountValue", PROPERTY_COUNT_VALUE);
        out.addProperty("monopoly.search.bankValue", BANK_VALUE);
        out.addProperty("monopoly.search.buildingRentBonusValue", BUILDING_RENT_BONUS_VALUE);
        out.addProperty("monopoly.search.opponentBuildingThreatValue", OPPONENT_BUILDING_THREAT_VALUE);
        out.addProperty("monopoly.search.conditionalThreatWeight", CONDITIONAL_THREAT_WEIGHT);
        out.addProperty("monopoly.search.conditionalThreatSelfMaxSets", CONDITIONAL_THREAT_SELF_MAX_SETS);
        out.addProperty("monopoly.search.conditionalThreatOpponentMinSets", CONDITIONAL_THREAT_OPPONENT_MIN_SETS);
        out.addProperty("monopoly.search.conditionalThreatOpponentMinAlmost", CONDITIONAL_THREAT_OPPONENT_MIN_ALMOST);
        out.addProperty("monopoly.search.passGoExpectedValue", PASS_GO_EXPECTED_VALUE);
        out.addProperty("monopoly.search.passGoOverflowPenalty", PASS_GO_OVERFLOW_PENALTY);
        out.addProperty("monopoly.search.passGoDepositBonus", PASS_GO_DEPOSIT_BONUS);
        out.addProperty("monopoly.search.lowBirthdayPenalty", LOW_BIRTHDAY_PENALTY);
        out.addProperty("monopoly.search.birthdayExpectedPaidMultiplier", BIRTHDAY_EXPECTED_PAID_MULTIPLIER);
        out.addProperty("monopoly.search.rentExpectedPaidMultiplier", RENT_EXPECTED_PAID_MULTIPLIER);
        out.addProperty("monopoly.search.debtExpectedPaidMultiplier", DEBT_EXPECTED_PAID_MULTIPLIER);
        out.addProperty("monopoly.search.stealPropertyBonus", STEAL_PROPERTY_BONUS);
        out.addProperty("monopoly.search.stealCompletionGainMultiplier", STEAL_COMPLETION_GAIN_MULTIPLIER);
        out.addProperty("monopoly.search.stealOppCompletionLossMultiplier", STEAL_OPP_COMPLETION_LOSS_MULTIPLIER);
        out.addProperty("monopoly.search.stealTakeValueMultiplier", STEAL_TAKE_VALUE_MULTIPLIER);
        out.addProperty("monopoly.search.stealWildBonus", STEAL_WILD_BONUS);
        out.addProperty("monopoly.search.forcedDealBonus", FORCED_DEAL_BONUS);
        out.addProperty("monopoly.search.buildingActionBonus", BUILDING_ACTION_BONUS);
        out.addProperty("monopoly.search.rentActionBonus", RENT_ACTION_BONUS);
        out.addProperty("monopoly.search.wildDeployPenalty", WILD_DEPLOY_PENALTY);
        out.addProperty("monopoly.search.wildShortSetPenalty", WILD_SHORT_SET_PENALTY);
        out.addProperty("monopoly.search.wildCompletionPenalty", WILD_COMPLETION_PENALTY);
        out.addProperty("monopoly.search.wildOverfullSetPenalty", WILD_OVERFULL_SET_PENALTY);
        out.addProperty("monopoly.search.boardAwareOverflowDiscard", BOARD_AWARE_OVERFLOW_DISCARD);
        out.addProperty("monopoly.search.boardAwarePayment", BOARD_AWARE_PAYMENT);
        out.addProperty("monopoly.search.paymentCompleteSetBreakPenalty", PAYMENT_COMPLETE_SET_BREAK_PENALTY);
        out.addProperty("monopoly.search.paymentNearSetBreakPenalty", PAYMENT_NEAR_SET_BREAK_PENALTY);
        out.addProperty("monopoly.search.maxPaymentCandidates", MAX_PAYMENT_CANDIDATES);
        out.addProperty("monopoly.search.responseTenantThreshold", RESPONSE_TENANT_THRESHOLD);
        out.addProperty("monopoly.search.responseCounterThreshold", RESPONSE_COUNTER_THRESHOLD);
        out.addProperty("monopoly.search.sameEffectHardTargetMargin", SAME_EFFECT_HARD_TARGET_MARGIN);
        out.addProperty("monopoly.search.hardRentFallbackMargin", HARD_RENT_FALLBACK_MARGIN);
        out.addProperty("monopoly.search.sameEffectHardTargetEffects", effectSetString(SAME_EFFECT_HARD_TARGET_EFFECTS));
        out.addProperty("monopoly.search.correctorModelPath", CORRECTOR_MODEL_PATH);
        out.addProperty("monopoly.search.correctorMinScoreGap", CORRECTOR_MIN_SCORE_GAP);
        out.addProperty("monopoly.search.correctorMaxLookaheadGap", CORRECTOR_MAX_LOOKAHEAD_GAP);
        out.addProperty("monopoly.search.correctorAllowLowerLookaheadScore", CORRECTOR_ALLOW_LOWER_LOOKAHEAD_SCORE);
        out.addProperty("monopoly.search.trace.includeMemento", TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO);
        out.addProperty("monopoly.search.correctorAllowedEffects", effectSetString(CORRECTOR_ALLOWED_EFFECTS));
        out.addProperty("monopoly.search.correctorAllowedActionTypes", effectSetString(CORRECTOR_ALLOWED_ACTION_TYPES));
        return out;
    }

    /**
     * Optional MDSP helper for remote LLMs. It exposes the same one-ply search
     * prior used by this strategy without choosing or mutating the live game.
     */
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

        SearchLookaheadAiPlayStrategy scorer =
                new SearchLookaheadAiPlayStrategy(DecisionTraceSink.NONE, "");
        List<ScoredCandidate> scoredCandidates = new ArrayList<>();
        ScoredCandidate best = null;
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            double score = scorer.evaluateCandidate(controller, bot, candidate);
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
            adjustments.addProperty(id, candidateAdjustment(bot, scored.candidate()));
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

        PlayActionRequest hardRequest = hardRequest(bot, context);
        candidates = prune(candidates, hardRequest);

        List<ScoredCandidate> scoredCandidates = new ArrayList<>();
        ScoredCandidate best = null;
        ScoredCandidate immediateBest = null;
        ScoredCandidate immediateBestRolloutScore = null;
        boolean needsImmediateBest = ROLLOUT_REMAINING_TURN && ROLLOUT_OVERRIDE_MARGIN > 0d;
        ScoredCandidate hardChoice = null;
        String hardKey = requestKey(hardRequest);
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            double score = evaluateCandidate(controller, bot, candidate);
            ScoredCandidate scored = new ScoredCandidate(candidate, score);
            scoredCandidates.add(scored);
            if (needsImmediateBest) {
                double immediateScore = evaluateImmediate(controller, bot.getPlayerId(), candidate)
                        + DeepSeekAiPlayStrategy.candidateScore(candidate) * 0.03d;
                ScoredCandidate immediateScored = new ScoredCandidate(candidate, immediateScore);
                if (immediateBest == null || immediateScored.score() > immediateBest.score()) {
                    immediateBest = immediateScored;
                    immediateBestRolloutScore = scored;
                }
            }
            if (!hardKey.isBlank() && hardKey.equals(requestKey(candidate.request()))) {
                hardChoice = scored;
            }
            if (best == null || scored.score() > best.score()) {
                best = scored;
            }
        }
        if (best == null || best.score() <= INVALID_SCORE / 2d) {
            return fallback.tryPlayOneCard(bot, context, bridge);
        }

        JsonObject traceContext = promptContext(bot, context, candidates);
        ScoredCandidate rawRolloutBest = best;
        ScoredCandidate rolloutImmediateBest = immediateBestRolloutScore;
        best = chooseWithRolloutOverrideMargin(best, rolloutImmediateBest);
        ScoredCandidate rolloutGateChoice = best;
        ScoredCandidate marginChoice = chooseWithHardMargin(best, hardChoice);
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
        JsonObject recordedTraceContext = contextWithSourcePolicy(
                bot,
                traceContext,
                scoredCandidates,
                best,
                hardChoice,
                chosen,
                rawRolloutBest,
                rolloutImmediateBest,
                rolloutGateChoice);
        recordedTraceContext = withCounterfactualMemento(controller, recordedTraceContext);
        long traceStateSequence = context == null ? 0L : context.getStateSequence();
        try {
            bridge.submitPlayAction(chosen.candidate().request());
            recordPlayTrace(
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

    private static JsonObject withCounterfactualMemento(
            GameController controller,
            JsonObject traceContext) {
        JsonObject out = traceContext == null ? new JsonObject() : traceContext.deepCopy();
        if (!TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO || controller == null) {
            return out;
        }
        JsonObject counterfactual = new JsonObject();
        counterfactual.addProperty("schema", "monopoly-deal-counterfactual-v1");
        counterfactual.addProperty("capture", "before_choice");
        counterfactual.addProperty("mementoJson", GameSessionMemento.capture(controller).toJson());
        out.add("counterfactual", counterfactual);
        return out;
    }

    @Override
    public AiHeuristics.AiResponseDecision chooseResponse(
            AIPlayer bot,
            GameContext context,
            boolean counterRole) {
        AiHeuristics.AiResponseDecision fallbackDecision =
                AiHeuristics.chooseResponse(bot, context, counterRole);
        AiHeuristics.AiResponseDecision chosen = fallbackDecision;
        if (RESPONSE_TENANT_THRESHOLD == 3 && RESPONSE_COUNTER_THRESHOLD == 5) {
            recordResponseTrace(bot, context, counterRole, fallbackDecision, chosen);
            return chosen;
        }
        chosen = chooseResponseWithThresholds(
                bot,
                context,
                counterRole,
                RESPONSE_TENANT_THRESHOLD,
                RESPONSE_COUNTER_THRESHOLD);
        recordResponseTrace(bot, context, counterRole, fallbackDecision, chosen);
        return chosen;
    }

    @Override
    public PaymentSettlement.PaymentChoice choosePayment(
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        if (!BOARD_AWARE_PAYMENT || bot == null || amountDue <= 0) {
            recordPaymentTrace(bot, context, creditor, amountDue, fallbackChoice, fallbackChoice);
            return fallbackChoice;
        }
        PaymentSettlement.PaymentChoice chosen = chooseBoardAwarePayment(bot, amountDue, fallbackChoice);
        recordPaymentTrace(bot, context, creditor, amountDue, fallbackChoice, chosen);
        return chosen;
    }

    private PaymentSettlement.PaymentChoice chooseBoardAwarePayment(
            AIPlayer bot,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        PaymentSettlement.PaymentChoice best = boardAwarePaymentChoice(bot, amountDue);
        if (best == null) {
            return fallbackChoice;
        }
        return best;
    }

    private static PaymentSettlement.PaymentChoice boardAwarePaymentChoice(
            Player debtor,
            int amountDue) {
        List<Card> payable = payableCards(debtor, amountDue);
        if (payable.isEmpty()) {
            return new PaymentSettlement.PaymentChoice(List.of(), 0);
        }
        int total = payable.stream().mapToInt(PayableCards::valueOf).sum();
        if (total < amountDue) {
            return new PaymentSettlement.PaymentChoice(List.copyOf(payable), total);
        }
        return bestBoardAwarePaymentChoice(debtor, payable, amountDue, total);
    }

    private static PaymentSettlement.PaymentChoice bestBoardAwarePaymentChoice(
            Player debtor,
            List<Card> payable,
            int amountDue,
            int total) {
        PaymentSettlement.PaymentChoice[] bestByAmount =
                new PaymentSettlement.PaymentChoice[total + 1];
        double[] bestScores = new double[total + 1];
        Arrays.fill(bestScores, Double.POSITIVE_INFINITY);
        bestByAmount[0] = new PaymentSettlement.PaymentChoice(List.of(), 0);
        bestScores[0] = 0d;

        for (Card card : payable) {
            int value = PayableCards.valueOf(card);
            if (value <= 0) {
                continue;
            }
            double cardScore = value + paymentCardDamage(debtor, card);
            for (int amount = total - value; amount >= 0; amount--) {
                PaymentSettlement.PaymentChoice previous = bestByAmount[amount];
                if (previous == null) {
                    continue;
                }
                int nextAmount = amount + value;
                List<Card> nextCards = new ArrayList<>(previous.cards());
                nextCards.add(card);
                PaymentSettlement.PaymentChoice next =
                        new PaymentSettlement.PaymentChoice(List.copyOf(nextCards), nextAmount);
                double nextScore = bestScores[amount] + cardScore;
                if (isBetterPaymentChoice(nextScore, next, bestScores[nextAmount], bestByAmount[nextAmount])) {
                    bestByAmount[nextAmount] = next;
                    bestScores[nextAmount] = nextScore;
                }
            }
        }

        PaymentSettlement.PaymentChoice best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int amount = amountDue; amount <= total; amount++) {
            PaymentSettlement.PaymentChoice choice = bestByAmount[amount];
            if (choice == null || choice.cards().isEmpty()) {
                continue;
            }
            double score = bestScores[amount];
            if (isBetterPaymentChoice(score, choice, bestScore, best)) {
                best = choice;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean isBetterPaymentChoice(
            double candidateScore,
            PaymentSettlement.PaymentChoice candidate,
            double incumbentScore,
            PaymentSettlement.PaymentChoice incumbent) {
        if (candidate == null) {
            return false;
        }
        if (incumbent == null) {
            return true;
        }
        int c = Double.compare(candidateScore, incumbentScore);
        if (c != 0) {
            return c < 0;
        }
        c = Integer.compare(candidate.amountPaid(), incumbent.amountPaid());
        if (c != 0) {
            return c < 0;
        }
        c = Integer.compare(candidate.cards().size(), incumbent.cards().size());
        if (c != 0) {
            return c < 0;
        }
        return paymentChoiceKey(candidate).compareTo(paymentChoiceKey(incumbent)) < 0;
    }

    private static List<Card> payableCards(Player debtor, int amountDue) {
        if (debtor == null) {
            return List.of();
        }
        List<Card> bank = new ArrayList<>();
        int bankTotal = 0;
        for (Card card : debtor.getBankCardsView()) {
            int value = PayableCards.valueOf(card);
            if (card != null && value > 0) {
                bank.add(card);
                bankTotal += value;
            }
        }
        List<Card> out = new ArrayList<>(bank);
        if (bankTotal < amountDue) {
            for (PropertyCard property : debtor.getPropertyCardsView()) {
                if (property != null && PayableCards.valueOf(property) > 0) {
                    out.add(property);
                }
            }
        }
        return out;
    }

    private static void enumeratePaymentChoices(
            List<Card> payable,
            int index,
            List<Card> chosen,
            int sum,
            int amountDue,
            int totalPayable,
            List<PaymentSettlement.PaymentChoice> out) {
        if (out.size() >= MAX_PAYMENT_CANDIDATES) {
            return;
        }
        boolean legal = sum >= amountDue || (totalPayable < amountDue && sum == totalPayable);
        if (legal && !chosen.isEmpty()) {
            out.add(new PaymentSettlement.PaymentChoice(List.copyOf(chosen), sum));
            if (out.size() >= MAX_PAYMENT_CANDIDATES) {
                return;
            }
        }
        if (index >= payable.size()) {
            return;
        }
        for (int i = index; i < payable.size(); i++) {
            Card card = payable.get(i);
            chosen.add(card);
            enumeratePaymentChoices(
                    payable,
                    i + 1,
                    chosen,
                    sum + PayableCards.valueOf(card),
                    amountDue,
                    totalPayable,
                    out);
            chosen.remove(chosen.size() - 1);
            if (out.size() >= MAX_PAYMENT_CANDIDATES) {
                return;
            }
        }
    }

    private static double paymentChoiceScore(
            Player debtor,
            PaymentSettlement.PaymentChoice choice) {
        if (choice == null) {
            return Double.MAX_VALUE;
        }
        return choice.amountPaid() + paymentBoardDamage(debtor, choice.cards());
    }

    private static double paymentBoardDamage(Player debtor, List<Card> cards) {
        if (debtor == null || cards == null || cards.isEmpty()) {
            return 0d;
        }
        double damage = 0d;
        for (Card card : cards) {
            damage += paymentCardDamage(debtor, card);
        }
        return damage;
    }

    private static double paymentCardDamage(Player debtor, Card card) {
        if (card instanceof PropertyCard property) {
            return propertyPaymentDamage(debtor, property);
        }
        return 0d;
    }

    private static double propertyPaymentDamage(Player debtor, PropertyCard property) {
        String color = paymentPropertyColor(property);
        if (color == null || color.isBlank()) {
            return PayableCards.valueOf(property) * 0.1d;
        }
        int need = Math.max(1, PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3));
        int effective = PropertySetCalculator.effectiveCountForColor(debtor.getPropertyCardsView(), color);
        double damage = PayableCards.valueOf(property) * 0.1d;
        if (effective >= need) {
            damage += PAYMENT_COMPLETE_SET_BREAK_PENALTY;
        } else if (effective == need - 1) {
            damage += PAYMENT_NEAR_SET_BREAK_PENALTY;
        }
        if (property instanceof PropertyWildCard) {
            damage += 0.5d;
        }
        return damage;
    }

    private static String paymentPropertyColor(PropertyCard property) {
        if (property instanceof PropertyWildCard wild) {
            return trim(wild.getAssignedColorKey()).toUpperCase(Locale.ROOT);
        }
        return trim(property.getColorGroup()).toUpperCase(Locale.ROOT);
    }

    private static String paymentChoiceKey(PaymentSettlement.PaymentChoice choice) {
        if (choice == null || choice.cards() == null) {
            return "";
        }
        return choice.cards().stream()
                .map(card -> trim(card.getId()))
                .sorted()
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    private void recordResponseTrace(
            AIPlayer bot,
            GameContext context,
            boolean counterRole,
            AiHeuristics.AiResponseDecision fallbackDecision,
            AiHeuristics.AiResponseDecision chosen) {
        if (!shouldRecordAuxiliaryTrace(bot, context)) {
            return;
        }
        List<SimulationDecisionCandidate> candidates = new ArrayList<>();
        JsonObject passPayload = new JsonObject();
        passPayload.addProperty("playJustSayNo", false);
        candidates.add(new SimulationDecisionCandidate(
                "PASS",
                "Pass Just Say No response.",
                passPayload));
        PlayActionRequest playRequest = responseTracePlayRequest(fallbackDecision, chosen);
        if (playRequest != null) {
            JsonObject playPayload = GSON.toJsonTree(playRequest).getAsJsonObject();
            playPayload.addProperty("playJustSayNo", true);
            candidates.add(new SimulationDecisionCandidate(
                    "PLAY_JSN",
                    "Play Just Say No.",
                    playPayload));
        }
        String choiceId = chosen != null && chosen.playWaiver() ? "PLAY_JSN" : "PASS";
        JsonObject contextJson = auxiliaryContextWithMemento(
                DeepSeekAiPlayStrategy.buildResponsePrompt(bot, context, counterRole, fallbackDecision),
                context);
        contextJson.addProperty("counterRole", counterRole);
        contextJson.addProperty("fallbackPlayJustSayNo",
                fallbackDecision != null && fallbackDecision.playWaiver());
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("fallbackChoiceId",
                fallbackDecision != null && fallbackDecision.playWaiver() ? "PLAY_JSN" : "PASS");
        metadata.addProperty("responseTenantThreshold", RESPONSE_TENANT_THRESHOLD);
        metadata.addProperty("responseCounterThreshold", RESPONSE_COUNTER_THRESHOLD);
        recordAuxiliaryTrace(bot, context, "JUST_SAY_NO", contextJson, candidates, choiceId, metadata);
    }

    private static PlayActionRequest responseTracePlayRequest(
            AiHeuristics.AiResponseDecision fallbackDecision,
            AiHeuristics.AiResponseDecision chosen) {
        if (chosen != null && chosen.request() != null) {
            return chosen.request();
        }
        if (fallbackDecision != null && fallbackDecision.request() != null) {
            return fallbackDecision.request();
        }
        return null;
    }

    private void recordPaymentTrace(
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice,
            PaymentSettlement.PaymentChoice chosenChoice) {
        if (!shouldRecordAuxiliaryTrace(bot, context)) {
            return;
        }
        List<Card> payable = payableCards(bot, amountDue);
        List<PaymentSettlement.PaymentChoice> choices = new ArrayList<>();
        enumeratePaymentChoices(
                payable,
                0,
                new ArrayList<>(),
                0,
                amountDue,
                payable.stream().mapToInt(PayableCards::valueOf).sum(),
                choices);
        addPaymentTraceChoice(choices, fallbackChoice);
        addPaymentTraceChoice(choices, chosenChoice);
        List<SimulationDecisionCandidate> candidates = new ArrayList<>();
        String fallbackKey = paymentChoiceKey(fallbackChoice);
        String chosenKey = paymentChoiceKey(chosenChoice);
        String fallbackId = "";
        String choiceId = "";
        int seq = 1;
        Set<String> seen = new HashSet<>();
        for (PaymentSettlement.PaymentChoice choice : choices) {
            String key = paymentChoiceKey(choice);
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            String id = "c" + seq++;
            if (key.equals(fallbackKey)) {
                fallbackId = id;
            }
            if (key.equals(chosenKey)) {
                choiceId = id;
            }
            candidates.add(new SimulationDecisionCandidate(
                    id,
                    "Pay " + choice.amountPaid() + "M with " + key + ".",
                    cardIdsPayload(choice.cards(), choice.amountPaid())));
        }
        if (choiceId.isBlank()) {
            choiceId = fallbackId.isBlank() ? "c1" : fallbackId;
        }
        JsonObject contextJson = auxiliaryContextWithMemento(
                DeepSeekAiPlayStrategy.buildPaymentPrompt(
                        bot, context, creditor, amountDue, payable, fallbackChoice),
                context);
        contextJson.addProperty("amountDueM", amountDue);
        contextJson.addProperty("creditorPlayerId", creditor == null ? "" : creditor.getPlayerId());
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("fallbackChoiceId", fallbackId);
        metadata.addProperty("boardAwarePayment", BOARD_AWARE_PAYMENT);
        recordAuxiliaryTrace(bot, context, "PAYMENT", contextJson, candidates, choiceId, metadata);
    }

    private static void addPaymentTraceChoice(
            List<PaymentSettlement.PaymentChoice> choices,
            PaymentSettlement.PaymentChoice choice) {
        if (choice != null && choice.cards() != null && !choice.cards().isEmpty()) {
            choices.add(choice);
        }
    }

    private void recordOverflowTrace(
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards,
            List<Card> chosenCards) {
        if (!shouldRecordAuxiliaryTrace(bot, context)) {
            return;
        }
        int need = bot == null ? 0 : Math.max(0, bot.getHandCardCount() - limit);
        List<List<Card>> choices = new ArrayList<>();
        enumerateDiscardChoices(bot == null ? List.of() : bot.getHandCardsView(), need, 0, new ArrayList<>(), choices);
        addDiscardTraceChoice(choices, fallbackCards, need);
        addDiscardTraceChoice(choices, chosenCards, need);
        String fallbackKey = cardIds(fallbackCards);
        String chosenKey = cardIds(chosenCards);
        String fallbackId = "";
        String choiceId = "";
        int seq = 1;
        Set<String> seen = new HashSet<>();
        List<SimulationDecisionCandidate> candidates = new ArrayList<>();
        for (List<Card> choice : choices) {
            String key = cardIds(choice);
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            String id = "c" + seq++;
            if (key.equals(fallbackKey)) {
                fallbackId = id;
            }
            if (key.equals(chosenKey)) {
                choiceId = id;
            }
            candidates.add(new SimulationDecisionCandidate(
                    id,
                    "Discard " + key + ".",
                    cardIdsPayload(choice, 0)));
        }
        if (choiceId.isBlank()) {
            choiceId = fallbackId.isBlank() ? "c1" : fallbackId;
        }
        JsonObject contextJson = auxiliaryContextWithMemento(
                DeepSeekAiPlayStrategy.buildDiscardPrompt(bot, context, limit, need, fallbackCards),
                context);
        contextJson.addProperty("handLimit", limit);
        contextJson.addProperty("discardCount", need);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("fallbackChoiceId", fallbackId);
        metadata.addProperty("boardAwareOverflowDiscard", BOARD_AWARE_OVERFLOW_DISCARD);
        recordAuxiliaryTrace(bot, context, "OVERFLOW_DISCARD", contextJson, candidates, choiceId, metadata);
    }

    private static void enumerateDiscardChoices(
            List<Card> hand,
            int need,
            int index,
            List<Card> chosen,
            List<List<Card>> out) {
        if (out.size() >= MAX_PAYMENT_CANDIDATES || need <= 0) {
            return;
        }
        if (chosen.size() == need) {
            out.add(List.copyOf(chosen));
            return;
        }
        if (hand == null || index >= hand.size()) {
            return;
        }
        for (int i = index; i < hand.size(); i++) {
            chosen.add(hand.get(i));
            enumerateDiscardChoices(hand, need, i + 1, chosen, out);
            chosen.remove(chosen.size() - 1);
            if (out.size() >= MAX_PAYMENT_CANDIDATES) {
                return;
            }
        }
    }

    private static void addDiscardTraceChoice(List<List<Card>> choices, List<Card> cards, int need) {
        if (cards != null && cards.size() == need) {
            choices.add(List.copyOf(cards));
        }
    }

    private boolean shouldRecordAuxiliaryTrace(AIPlayer bot, GameContext context) {
        return bot != null && context != null && traceSink != null && traceSink != DecisionTraceSink.NONE;
    }

    private JsonObject auxiliaryContextWithMemento(String prompt, GameContext context) {
        JsonObject out = parsePrompt(prompt);
        if (context == null) {
            return out;
        }
        out.addProperty("roundNumber", context.getRoundNumber());
        out.addProperty("currentTurnPhase", currentPhase(context));
        out.addProperty("currentTurnPlayerId", context.getCurrentTurnPlayerId());
        String memento = trim(context.getAuxiliaryDecisionMementoJson());
        if (!memento.isBlank()) {
            JsonObject counterfactual = new JsonObject();
            counterfactual.addProperty("schema", "monopoly-deal-counterfactual-v1");
            counterfactual.addProperty("capture", "before_choice");
            counterfactual.addProperty("mementoJson", memento);
            out.add("counterfactual", counterfactual);
        }
        return out;
    }

    private static JsonObject parsePrompt(String prompt) {
        try {
            return JsonParser.parseString(prompt).getAsJsonObject();
        } catch (RuntimeException ex) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("rawPrompt", prompt == null ? "" : prompt);
            return fallback;
        }
    }

    private void recordAuxiliaryTrace(
            AIPlayer bot,
            GameContext context,
            String kind,
            JsonObject contextJson,
            List<SimulationDecisionCandidate> candidates,
            String choiceId,
            JsonObject metadata) {
        if (candidates == null || candidates.isEmpty() || choiceId == null || choiceId.isBlank()) {
            return;
        }
        String actorId = bot.getPlayerId();
        long seq = TRACE_SEQUENCE.incrementAndGet();
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                traceSessionId + "-" + actorId + "-" + kind + "-" + seq,
                traceSessionId,
                actorId,
                kind,
                context == null ? 0L : context.getStateSequence(),
                contextJson == null ? new JsonObject() : contextJson,
                candidates);
        try {
            traceSink.record(request, new SimulationDecisionResult(
                    request.getDecisionId(),
                    choiceId,
                    null,
                    metadata == null ? new JsonObject() : metadata));
        } catch (RuntimeException ex) {
            AiBattleLogger.log("Lookahead",
                    "auxiliary trace write skipped after successful choice: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }

    private static JsonObject cardIdsPayload(List<? extends Card> cards, int amountPaid) {
        JsonObject payload = new JsonObject();
        JsonArray ids = new JsonArray();
        if (cards != null) {
            for (Card card : cards) {
                ids.add(card.getId());
            }
        }
        payload.add("cardIds", ids);
        if (amountPaid > 0) {
            payload.addProperty("amountPaidM", amountPaid);
        }
        return payload;
    }

    private static String cardIds(List<? extends Card> cards) {
        if (cards == null) {
            return "";
        }
        return cards.stream()
                .map(Card::getId)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    static boolean defaultBoardAwarePaymentForTest() {
        return BOARD_AWARE_PAYMENT;
    }

    static PaymentSettlement.PaymentChoice boardAwarePaymentChoiceForTest(
            Player debtor,
            int amountDue) {
        return boardAwarePaymentChoice(debtor, amountDue);
    }

    static double paymentBoardDamageForTest(Player debtor, List<Card> cards) {
        return paymentBoardDamage(debtor, cards);
    }

    @Override
    public List<Card> chooseOverflowDiscards(
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards) {
        if (!BOARD_AWARE_OVERFLOW_DISCARD || bot == null || bot.getHandCardCount() <= limit) {
            recordOverflowTrace(bot, context, limit, fallbackCards, fallbackCards);
            return fallbackCards;
        }
        List<Card> hand = new ArrayList<>(bot.getHandCardsView());
        List<Card> chosen = new ArrayList<>();
        int effectiveLimit = Math.max(0, limit);
        while (hand.size() > effectiveLimit) {
            Card discard = hand.stream()
                    .min(Comparator
                            .comparingDouble((Card card) -> overflowDiscardRetentionScore(bot, card))
                            .thenComparing(card -> trim(card.getId())))
                    .orElse(hand.get(hand.size() - 1));
            hand.remove(discard);
            chosen.add(discard);
        }
        recordOverflowTrace(bot, context, limit, fallbackCards, chosen);
        return chosen;
    }

    private ScoredCandidate chooseWithHardMargin(
            ScoredCandidate best,
            ScoredCandidate hardChoice) {
        if (HARD_MARGIN < 0d || hardChoice == null) {
            return best;
        }
        return best.score() - hardChoice.score() >= HARD_MARGIN ? best : hardChoice;
    }

    private ScoredCandidate chooseWithRolloutOverrideMargin(
            ScoredCandidate best,
            ScoredCandidate immediateBest) {
        if (ROLLOUT_OVERRIDE_MARGIN <= 0d
                || !ROLLOUT_REMAINING_TURN
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
        return best.score() - immediateBest.score() >= ROLLOUT_OVERRIDE_MARGIN
                ? best
                : immediateBest;
    }

    private static boolean rolloutOverrideEffectAllowed(AiHeuristics.AiPlayCandidate candidate) {
        return ROLLOUT_OVERRIDE_ALLOWED_EFFECTS.isEmpty()
                || ROLLOUT_OVERRIDE_ALLOWED_EFFECTS.contains(candidateEffect(null, candidate));
    }

    private static boolean rolloutOverrideTransitionAllowed(
            AiHeuristics.AiPlayCandidate immediateBest,
            AiHeuristics.AiPlayCandidate rolloutBest) {
        if (ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS.isEmpty()) {
            return true;
        }
        String transition = candidateEffect(null, immediateBest)
                + "->"
                + candidateEffect(null, rolloutBest);
        return ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS.contains(transition.toUpperCase(Locale.ROOT));
    }

    private static void addRolloutGateMetadata(
            JsonObject metadata,
            ScoredCandidate rawRolloutBest,
            ScoredCandidate rolloutImmediateBest,
            ScoredCandidate rolloutGateChoice) {
        if (metadata == null || !ROLLOUT_REMAINING_TURN || ROLLOUT_OVERRIDE_MARGIN <= 0d) {
            return;
        }
        if (rawRolloutBest != null && rawRolloutBest.candidate() != null) {
            metadata.addProperty("rolloutRawBestId", rawRolloutBest.candidate().id());
            metadata.addProperty("rolloutRawBestScore", rawRolloutBest.score());
            metadata.addProperty("rolloutRawBestEffect", candidateEffect(null, rawRolloutBest.candidate()));
        }
        if (rolloutImmediateBest != null && rolloutImmediateBest.candidate() != null) {
            metadata.addProperty("rolloutImmediateBestId", rolloutImmediateBest.candidate().id());
            metadata.addProperty("rolloutImmediateBestScore", rolloutImmediateBest.score());
            metadata.addProperty("rolloutImmediateBestEffect", candidateEffect(null, rolloutImmediateBest.candidate()));
        }
        if (rawRolloutBest != null
                && rawRolloutBest.candidate() != null
                && rolloutImmediateBest != null
                && rolloutImmediateBest.candidate() != null) {
            double scoreGap = rawRolloutBest.score() - rolloutImmediateBest.score();
            metadata.addProperty("rolloutOverrideScoreGap", scoreGap);
            metadata.addProperty("rolloutOverrideEffectAllowed",
                    rolloutOverrideEffectAllowed(rawRolloutBest.candidate()));
            metadata.addProperty("rolloutOverrideTransitionAllowed",
                    rolloutOverrideTransitionAllowed(rolloutImmediateBest.candidate(), rawRolloutBest.candidate()));
            metadata.addProperty("rolloutOverrideSameCandidate",
                    rawRolloutBest.candidate().id().equals(rolloutImmediateBest.candidate().id()));
        }
        if (rolloutGateChoice != null && rolloutGateChoice.candidate() != null) {
            metadata.addProperty("rolloutGateChoiceId", rolloutGateChoice.candidate().id());
            metadata.addProperty("rolloutGateChoiceScore", rolloutGateChoice.score());
            metadata.addProperty("rolloutGateChoiceEffect", candidateEffect(null, rolloutGateChoice.candidate()));
        }
        metadata.addProperty("rolloutOverrideUsed",
                rawRolloutBest != null
                        && rawRolloutBest.candidate() != null
                        && rolloutGateChoice != null
                        && rolloutGateChoice.candidate() != null
                        && rawRolloutBest.candidate().id().equals(rolloutGateChoice.candidate().id())
                        && (rolloutImmediateBest == null
                        || rolloutImmediateBest.candidate() == null
                        || !rawRolloutBest.candidate().id().equals(rolloutImmediateBest.candidate().id())));
    }

    private ScoredCandidate chooseWithSameEffectHardTargetMargin(
            ScoredCandidate best,
            ScoredCandidate hardChoice,
            ScoredCandidate currentChoice) {
        if (SAME_EFFECT_HARD_TARGET_MARGIN < 0d
                || best == null
                || hardChoice == null
                || best.candidate() == null
                || hardChoice.candidate() == null) {
            return currentChoice;
        }
        if (!sameEffectDifferentTarget(best.candidate(), hardChoice.candidate())) {
            return currentChoice;
        }
        return best.score() - hardChoice.score() >= SAME_EFFECT_HARD_TARGET_MARGIN
                ? currentChoice
                : hardChoice;
    }

    private ScoredCandidate chooseWithHardRentFallbackMargin(
            ScoredCandidate best,
            ScoredCandidate hardChoice,
            ScoredCandidate currentChoice) {
        if (HARD_RENT_FALLBACK_MARGIN < 0d
                || best == null
                || hardChoice == null
                || best.candidate() == null
                || hardChoice.candidate() == null) {
            return currentChoice;
        }
        String hardEffect = candidateEffect(null, hardChoice.candidate());
        if (!"RENT".equals(hardEffect) && !"RENT_DUAL".equals(hardEffect)) {
            return currentChoice;
        }
        String bestEffect = candidateEffect(null, best.candidate());
        if (!"DEPLOY".equals(bestEffect) && !"PASS_GO".equals(bestEffect)) {
            return currentChoice;
        }
        return best.score() - hardChoice.score() >= HARD_RENT_FALLBACK_MARGIN
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
        metadata.addProperty("minScoreGap", CORRECTOR_MIN_SCORE_GAP);
        metadata.addProperty("maxLookaheadGap", CORRECTOR_MAX_LOOKAHEAD_GAP);
        JsonArray allowedEffects = new JsonArray();
        for (String effect : CORRECTOR_ALLOWED_EFFECTS) {
            allowedEffects.add(effect);
        }
        metadata.add("allowedEffects", allowedEffects);
        JsonArray allowedActionTypes = new JsonArray();
        for (String actionType : CORRECTOR_ALLOWED_ACTION_TYPES) {
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
                JsonObject payload = GSON.toJsonTree(scored.candidate().request()).getAsJsonObject();
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
        boolean override = correctorGap >= CORRECTOR_MIN_SCORE_GAP
                && lookaheadGap <= CORRECTOR_MAX_LOOKAHEAD_GAP
                && (CORRECTOR_ALLOW_LOWER_LOOKAHEAD_SCORE || lookaheadGap <= 0d);
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
        metadata.addProperty("hardMargin", HARD_MARGIN);
        metadata.addProperty("sameEffectHardTargetMargin", SAME_EFFECT_HARD_TARGET_MARGIN);
        metadata.addProperty("hardRentFallbackMargin", HARD_RENT_FALLBACK_MARGIN);
        metadata.addProperty("rolloutRemainingTurn", ROLLOUT_REMAINING_TURN);
        metadata.addProperty("rolloutOverrideMargin", ROLLOUT_OVERRIDE_MARGIN);
        metadata.addProperty("rolloutOverrideAllowedEffects", String.join(",", ROLLOUT_OVERRIDE_ALLOWED_EFFECTS));
        metadata.addProperty("rolloutPolicy", ROLLOUT_POLICY);
        addRolloutGateMetadata(metadata, rawRolloutBest, rolloutImmediateBest, rolloutGateChoice);
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
                    sameEffectHardTargetFallbackUsed(modelBest, hardChoice, currentChoice));
            metadata.addProperty("hardRentFallbackUsed",
                    hardRentFallbackUsed(modelBest, hardChoice, currentChoice));
        }
        JsonObject scores = new JsonObject();
        JsonObject adjustments = new JsonObject();
        if (scoredCandidates != null) {
            for (ScoredCandidate scored : scoredCandidates) {
                if (scored == null || scored.candidate() == null) {
                    continue;
                }
                scores.addProperty(scored.candidate().id(), scored.score());
                adjustments.addProperty(scored.candidate().id(), candidateAdjustment(bot, scored.candidate()));
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
            if (isCorrectorTacticalCandidate(scored.candidate())) {
                out.add(scored);
                seen.add(scored.candidate().id());
            }
        }
        return out;
    }

    private static boolean isCorrectorTacticalCandidate(AiHeuristics.AiPlayCandidate candidate) {
        if (candidate == null || candidate.request() == null) {
            return false;
        }
        String actionType = trim(candidate.request().getActionType()).toUpperCase(Locale.ROOT);
        if (!CORRECTOR_ALLOWED_ACTION_TYPES.contains(actionType)) {
            return false;
        }
        if (!"ACTION".equals(actionType)) {
            return true;
        }
        String effect = candidateEffect(null, candidate);
        return !effect.isBlank() && CORRECTOR_ALLOWED_EFFECTS.contains(effect);
    }

    private void recordPlayTrace(
            AIPlayer bot,
            long stateSequence,
            JsonObject contextJson,
            List<AiHeuristics.AiPlayCandidate> candidates,
            List<ScoredCandidate> scoredCandidates,
            ScoredCandidate modelBest,
            ScoredCandidate hardChoice,
            ScoredCandidate chosen,
            JsonObject correctorMetadata,
            ScoredCandidate rawRolloutBest,
            ScoredCandidate rolloutImmediateBest,
            ScoredCandidate rolloutGateChoice) {
        if (chosen == null || bot == null) {
            return;
        }
        String actorId = bot.getPlayerId();
        long seq = TRACE_SEQUENCE.incrementAndGet();
        List<SimulationDecisionCandidate> traceCandidates = new ArrayList<>();
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            traceCandidates.add(new SimulationDecisionCandidate(
                    candidate.id(),
                    candidate.summary(),
                    GSON.toJsonTree(candidate.request()).getAsJsonObject()));
        }
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                traceSessionId + "-" + actorId + "-LOOKAHEAD-" + seq,
                traceSessionId,
                actorId,
                "PLAY_CARD",
                stateSequence,
                contextJson == null ? new JsonObject() : contextJson,
                traceCandidates);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("score", chosen.score());
        metadata.addProperty("hardMargin", HARD_MARGIN);
        metadata.addProperty("sameEffectHardTargetMargin", SAME_EFFECT_HARD_TARGET_MARGIN);
        metadata.addProperty("hardRentFallbackMargin", HARD_RENT_FALLBACK_MARGIN);
        metadata.addProperty("rolloutRemainingTurn", ROLLOUT_REMAINING_TURN);
        metadata.addProperty("rolloutOverrideMargin", ROLLOUT_OVERRIDE_MARGIN);
        metadata.addProperty("rolloutOverrideAllowedEffects", String.join(",", ROLLOUT_OVERRIDE_ALLOWED_EFFECTS));
        metadata.addProperty("rolloutPolicy", ROLLOUT_POLICY);
        addRolloutGateMetadata(metadata, rawRolloutBest, rolloutImmediateBest, rolloutGateChoice);
        if (modelBest != null) {
            metadata.addProperty("modelBestId", modelBest.candidate().id());
            metadata.addProperty("modelBestScore", modelBest.score());
        }
        if (hardChoice != null) {
            metadata.addProperty("hardChoiceId", hardChoice.candidate().id());
            metadata.addProperty("hardChoiceScore", hardChoice.score());
            metadata.addProperty("hardFallbackUsed",
                    chosen.candidate().id().equals(hardChoice.candidate().id())
                            && modelBest != null
                            && !modelBest.candidate().id().equals(hardChoice.candidate().id()));
            metadata.addProperty("sameEffectHardTargetFallbackUsed",
                    sameEffectHardTargetFallbackUsed(modelBest, hardChoice, chosen));
            metadata.addProperty("hardRentFallbackUsed",
                    hardRentFallbackUsed(modelBest, hardChoice, chosen));
        }
        JsonObject scores = new JsonObject();
        JsonObject adjustments = new JsonObject();
        for (ScoredCandidate scored : scoredCandidates) {
            scores.addProperty(scored.candidate().id(), scored.score());
            adjustments.addProperty(scored.candidate().id(), candidateAdjustment(bot, scored.candidate()));
        }
        metadata.add("candidateScores", scores);
        metadata.add("candidateAdjustments", adjustments);
        if (correctorMetadata != null && correctorMetadata.size() > 0) {
            metadata.add("corrector", correctorMetadata);
        }
        try {
            traceSink.record(request, new SimulationDecisionResult(
                    request.getDecisionId(),
                    chosen.candidate().id(),
                    null,
                    metadata));
        } catch (RuntimeException ex) {
            AiBattleLogger.log("Lookahead",
                    "trace write skipped after successful choice: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }

    private double evaluateCandidate(
            GameController controller,
            AIPlayer sourceBot,
            AiHeuristics.AiPlayCandidate candidate) {
        CapturingSubject subject = new CapturingSubject();
        GameController clone = null;
        try {
            String actorId = sourceBot.getPlayerId();
            GameSessionMemento memento = GameSessionMemento.capture(controller);
            clone = GameSessionMemento.restore(subject, memento);
            clone.setSuppressAiAutoContinuation(true);
            setCloneAiStrategiesToHard(clone);
            Player actor = playerById(clone, actorId);
            boolean revealsHiddenDraw = requestMayRevealHiddenCards(actor, candidate.request());
            clone.handlePlayActionRequest(candidate.request());
            if (ROLLOUT_REMAINING_TURN && !revealsHiddenDraw) {
                rolloutRemainingTurn(clone, actorId);
            }
            if (ROLLOUT_NEXT_OPPONENT_TURN) {
                rolloutNextOpponentTurn(clone, actorId);
            }
            GameStateSnapshot snapshot = subject.last();
            if (snapshot == null) {
                return INVALID_SCORE;
            }
            return stateValue(snapshot, actorId)
                    + DeepSeekAiPlayStrategy.candidateScore(candidate) * 0.03d
                    + candidateAdjustment(actor, candidate);
        } catch (RuntimeException ex) {
            return INVALID_SCORE;
        } finally {
            if (clone != null) {
                clone.shutdown();
            }
        }
    }

    private void rolloutNextOpponentTurn(GameController clone, String actorId) {
        int guard = 0;
        while (guard++ < 6 && !clone.isSessionEndedPublic()) {
            Player current = clone.getCurrentPlayer();
            if (!(current instanceof AIPlayer ai) || actorId.equals(ai.getPlayerId())) {
                return;
            }
            GameContext context = clone.refreshAndGetAiDecisionContextForSimulation();
            String phase = currentPhase(context);
            if ("WAITING_FOR_RESPONSE".equals(phase)) {
                return;
            }
            if ("DRAW".equals(phase)) {
                clone.drawCards(ai, 2);
                return;
            }
            if ("END_TURN".equals(phase)) {
                clone.endTurn(ai);
                return;
            }
            if ("PLAY".equals(phase)
                    && context.remainingTurnActions() > 0
                    && !ai.getHandCardsView().isEmpty()) {
                RolloutStep step = tryFairRolloutPlay(clone, ai, context, actorId);
                if (step == RolloutStep.CONTINUE) {
                    continue;
                }
                if (step == RolloutStep.STOP_AFTER_HIDDEN_DRAW) {
                    return;
                }
            }
            clone.endTurn(ai);
            return;
        }
    }

    private void rolloutRemainingTurn(GameController clone, String actorId) {
        int guard = 0;
        while (guard++ < 3 && !clone.isSessionEndedPublic()) {
            Player current = clone.getCurrentPlayer();
            if (!(current instanceof AIPlayer ai) || !actorId.equals(ai.getPlayerId())) {
                return;
            }
            GameContext context = clone.refreshAndGetAiDecisionContextForSimulation();
            String phase = currentPhase(context);
            if ("WAITING_FOR_RESPONSE".equals(phase) || "DRAW".equals(phase)) {
                return;
            }
            if ("END_TURN".equals(phase) || ai.getHandCardsView().isEmpty()) {
                clone.endTurn(ai);
                return;
            }
            RolloutStep step = tryRolloutPlay(clone, ai, context, actorId);
            if (step == RolloutStep.CONTINUE) {
                continue;
            }
            if (step == RolloutStep.NO_PLAY) {
                clone.endTurn(ai);
            }
            return;
        }
    }

    private RolloutStep tryRolloutPlay(
            GameController clone,
            AIPlayer ai,
            GameContext context,
            String actorId) {
        if (!"search".equals(ROLLOUT_POLICY) && !"greedy".equals(ROLLOUT_POLICY)) {
            return tryFairHardRolloutPlay(clone, ai, context);
        }
        AiHeuristics.AiPlayCandidate candidate =
                bestImmediateRolloutCandidate(clone, ai, context, actorId);
        if (candidate == null) {
            return tryFairHardRolloutPlay(clone, ai, context);
        }
        try {
            boolean revealsHiddenDraw = requestMayRevealHiddenCards(ai, candidate.request());
            clone.handlePlayActionRequest(candidate.request());
            return revealsHiddenDraw ? RolloutStep.STOP_AFTER_HIDDEN_DRAW : RolloutStep.CONTINUE;
        } catch (RuntimeException ex) {
            return tryFairHardRolloutPlay(clone, ai, context);
        }
    }

    private RolloutStep tryFairRolloutPlay(
            GameController clone,
            AIPlayer ai,
            GameContext context,
            String actorId) {
        if ("search".equals(ROLLOUT_POLICY) || "greedy".equals(ROLLOUT_POLICY)) {
            return tryRolloutPlay(clone, ai, context, actorId);
        }
        return tryFairHardRolloutPlay(clone, ai, context);
    }

    private RolloutStep tryFairHardRolloutPlay(
            GameController clone,
            AIPlayer ai,
            GameContext context) {
        PlayActionRequest request = hardRequest(ai, context);
        if (request == null) {
            return RolloutStep.NO_PLAY;
        }
        boolean revealsHiddenDraw = requestMayRevealHiddenCards(ai, request);
        try {
            clone.handlePlayActionRequest(request);
            return revealsHiddenDraw ? RolloutStep.STOP_AFTER_HIDDEN_DRAW : RolloutStep.CONTINUE;
        } catch (RuntimeException ex) {
            return RolloutStep.NO_PLAY;
        }
    }

    private AiHeuristics.AiPlayCandidate bestImmediateRolloutCandidate(
            GameController controller,
            AIPlayer bot,
            GameContext context,
            String actorId) {
        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, context);
        if (candidates.isEmpty()) {
            return null;
        }
        PlayActionRequest hardRequest = hardRequest(bot, context);
        candidates = pruneToLimit(candidates, hardRequest, Math.max(4, MAX_ROLLOUT_CANDIDATES));
        ScoredCandidate best = null;
        ScoredCandidate hardChoice = null;
        String hardKey = requestKey(hardRequest);
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            double score = evaluateImmediate(controller, actorId, candidate)
                    + DeepSeekAiPlayStrategy.candidateScore(candidate) * 0.02d;
            ScoredCandidate scored = new ScoredCandidate(candidate, score);
            if (!hardKey.isBlank() && hardKey.equals(requestKey(candidate.request()))) {
                hardChoice = scored;
            }
            if (best == null || scored.score() > best.score()) {
                best = scored;
            }
        }
        if (best == null || best.score() <= INVALID_SCORE / 2d) {
            return null;
        }
        return chooseWithHardMargin(best, hardChoice).candidate();
    }

    private double evaluateImmediate(
            GameController controller,
            String actorId,
            AiHeuristics.AiPlayCandidate candidate) {
        CapturingSubject subject = new CapturingSubject();
        GameController clone = null;
        try {
            GameSessionMemento memento = GameSessionMemento.capture(controller);
            clone = GameSessionMemento.restore(subject, memento);
            clone.setSuppressAiAutoContinuation(true);
            setCloneAiStrategiesToHard(clone);
            Player actor = playerById(clone, actorId);
            clone.handlePlayActionRequest(candidate.request());
            GameStateSnapshot snapshot = subject.last();
            return snapshot == null
                    ? INVALID_SCORE
                    : stateValue(snapshot, actorId) + candidateAdjustment(actor, candidate);
        } catch (RuntimeException ex) {
            return INVALID_SCORE;
        } finally {
            if (clone != null) {
                clone.shutdown();
            }
        }
    }

    private void setCloneAiStrategiesToHard(GameController clone) {
        for (Player player : clone.getSessionPlayersView()) {
            if (player instanceof AIPlayer ai) {
                ai.setPlayStrategy(new BlindHardRolloutStrategy());
            }
        }
    }

    private static String currentPhase(GameContext context) {
        return context.getCurrentTurnPhase() == null
                ? ""
                : context.getCurrentTurnPhase().trim().toUpperCase(Locale.ROOT);
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

    private static double stateValue(GameStateSnapshot snapshot, String actorId) {
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
        List<GameStateSnapshot.PlayerPublicSummary> opponents = new ArrayList<>();
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
            threatPenalty += threatProfile.value() * CONDITIONAL_THREAT_WEIGHT;
        }
        double tempo = snapshot.getActionsRemainingThisTurn() * 18d
                + Math.max(0, 7 - self.getHandCount()) * 10d
                - snapshot.getOverflowDiscardCount() * 120d;

        return selfValue
                - maxOpponent * MAX_OPPONENT_WEIGHT
                - avgOpponent * AVG_OPPONENT_WEIGHT
                - threatPenalty * THREAT_WEIGHT
                + tempo;
    }

    private static double playerValue(GameStateSnapshot.PlayerPublicSummary player) {
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
        return completeSets * COMPLETE_SET_VALUE
                + lockedColors * 1_200d
                + nearComplete * NEAR_COMPLETE_VALUE
                + cappedProgress * CAPPED_PROGRESS_VALUE
                + player.getPropertyCount() * PROPERTY_COUNT_VALUE
                + buildingRentBonusM(player.getPropertyZoneCards()) * BUILDING_RENT_BONUS_VALUE
                + player.getBankTotalValueM() * BANK_VALUE
                + player.getHandCount() * 22d
                - player.getActionZoneCount() * 10d;
    }

    private static int buildingRentBonusM(JsonArray propertyCards) {
        if (propertyCards == null || propertyCards.isEmpty()) {
            return 0;
        }
        int bonus = 0;
        for (JsonElement element : propertyCards) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject card = element.getAsJsonObject();
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

    private static ThreatProfile opponentThreat(List<GameStateSnapshot.PlayerPublicSummary> opponents) {
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
                    + buildingRentBonusM(opponent.getPropertyZoneCards()) * OPPONENT_BUILDING_THREAT_VALUE
                    + opponent.getBankTotalValueM() * 18d;
            if (value > best.value()) {
                best = new ThreatProfile(value, opponent.getCompletePropertySets(), almost);
            }
        }
        return best;
    }

    private static boolean shouldApplyConditionalThreat(
            GameStateSnapshot.PlayerPublicSummary self,
            ThreatProfile threatProfile) {
        if (CONDITIONAL_THREAT_WEIGHT == 0d || self == null || threatProfile.value() <= 0d) {
            return false;
        }
        if (self.getCompletePropertySets() > CONDITIONAL_THREAT_SELF_MAX_SETS) {
            return false;
        }
        return threatProfile.completeSets() >= CONDITIONAL_THREAT_OPPONENT_MIN_SETS
                || threatProfile.nearCompleteColors() >= CONDITIONAL_THREAT_OPPONENT_MIN_ALMOST;
    }

    private PlayActionRequest hardRequest(AIPlayer bot, GameContext context) {
        RecordingBridge recording = new RecordingBridge();
        try {
            if (fallback.tryPlayOneCard(bot, context, recording)) {
                return recording.request;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    private static List<AiHeuristics.AiPlayCandidate> prune(
            List<AiHeuristics.AiPlayCandidate> candidates,
            PlayActionRequest hardRequest) {
        int limit = Math.max(4, MAX_CANDIDATES);
        return pruneToLimit(candidates, hardRequest, limit);
    }

    private static List<AiHeuristics.AiPlayCandidate> pruneToLimit(
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
            keys.add(requestKey(candidate.request()));
        }
        addStructuredTacticalCandidates(candidates, out, keys, limit);
        addReservedTacticalCandidates(candidates, out, keys, limit);
        String hardKey = requestKey(hardRequest);
        if (!hardKey.isBlank() && !keys.contains(hardKey)) {
            for (AiHeuristics.AiPlayCandidate candidate : candidates) {
                if (hardKey.equals(requestKey(candidate.request()))) {
                    out.add(candidate);
                    break;
                }
            }
        }
        return out;
    }

    private static void addStructuredTacticalCandidates(
            List<AiHeuristics.AiPlayCandidate> candidates,
            List<AiHeuristics.AiPlayCandidate> out,
            Set<String> keys,
            int limit) {
        if (STRUCTURED_TACTICAL_RESERVED_CANDIDATES <= 0 || candidates == null || candidates.isEmpty()) {
            return;
        }
        int hardLimit = Math.max(limit, limit + STRUCTURED_TACTICAL_RESERVED_CANDIDATES);
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
            String key = requestKey(item.candidate().request());
            if (key.isBlank() || keys.contains(key)) {
                continue;
            }
            out.add(item.candidate());
            keys.add(key);
        }
    }

    private static int structuredTacticalPriority(AiHeuristics.AiPlayCandidate candidate) {
        if (candidate == null || candidate.request() == null) {
            return 0;
        }
        String effect = candidateEffect(null, candidate);
        if ("STEAL_PROPERTY".equals(effect)) {
            int completionGain = numberAfter(candidate.summary(), "completionGain=");
            int oppCompletionLoss = numberAfter(candidate.summary(), "oppCompletionLoss=");
            int takeValue = numberAfter(candidate.summary(), "takeValue=");
            boolean wild = containsMarkerValue(candidate.summary(), "wild=", "true");
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
            int netScore = numberAfter(candidate.summary(), "netScore=");
            int completionGain = numberAfter(candidate.summary(), "completionGain=");
            int oppCompletionLoss = numberAfter(candidate.summary(), "oppCompletionLoss=");
            int materialGain = numberAfter(candidate.summary(), "materialGain=");
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

    private static double prunePriorityScore(AiHeuristics.AiPlayCandidate candidate) {
        double score = DeepSeekAiPlayStrategy.candidateScore(candidate);
        if (STRUCTURED_PRUNE_PRIORITY_WEIGHT != 0d) {
            score += structuredTacticalPriority(candidate) * STRUCTURED_PRUNE_PRIORITY_WEIGHT;
        }
        return score;
    }

    private static void addReservedTacticalCandidates(
            List<AiHeuristics.AiPlayCandidate> candidates,
            List<AiHeuristics.AiPlayCandidate> out,
            Set<String> keys,
            int limit) {
        if (TACTICAL_RESERVED_CANDIDATES <= 0 || candidates == null || candidates.isEmpty()) {
            return;
        }
        int hardLimit = Math.max(limit, limit + TACTICAL_RESERVED_CANDIDATES);
        List<AiHeuristics.AiPlayCandidate> tactical = new ArrayList<>();
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            if (isReservedTacticalCandidate(candidate)) {
                tactical.add(candidate);
            }
        }
        tactical.sort(Comparator.comparingInt(
                (AiHeuristics.AiPlayCandidate candidate) ->
                        DeepSeekAiPlayStrategy.candidateScore(candidate)).reversed());
        for (AiHeuristics.AiPlayCandidate candidate : tactical) {
            if (out.size() >= hardLimit) {
                return;
            }
            String key = requestKey(candidate.request());
            if (key.isBlank() || keys.contains(key)) {
                continue;
            }
            out.add(candidate);
            keys.add(key);
        }
    }

    private static boolean isReservedTacticalCandidate(AiHeuristics.AiPlayCandidate candidate) {
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
        if (!"DEPLOY".equalsIgnoreCase(trim(candidate.request().getActionType()))) {
            return false;
        }
        int completionScore = numberAfter(summary, "COMPLETIONSCORE=");
        return completionScore >= 100;
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

    static double defaultBuildingActionBonusForTest() {
        return BUILDING_ACTION_BONUS;
    }

    static double defaultBuildingRentBonusValueForTest() {
        return BUILDING_RENT_BONUS_VALUE;
    }

    static double defaultOpponentBuildingThreatValueForTest() {
        return OPPONENT_BUILDING_THREAT_VALUE;
    }

    static double defaultPassGoDepositBonusForTest() {
        return PASS_GO_DEPOSIT_BONUS;
    }

    static double defaultSameEffectHardTargetMarginForTest() {
        return SAME_EFFECT_HARD_TARGET_MARGIN;
    }

    static double defaultHardRentFallbackMarginForTest() {
        return HARD_RENT_FALLBACK_MARGIN;
    }

    static double defaultRolloutOverrideMarginForTest() {
        return ROLLOUT_OVERRIDE_MARGIN;
    }

    static int defaultRolloutOverrideAllowedEffectsForTest() {
        return ROLLOUT_OVERRIDE_ALLOWED_EFFECTS.size();
    }

    static int defaultRolloutOverrideAllowedTransitionsForTest() {
        return ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS.size();
    }

    static boolean rolloutOverrideTransitionAllowedForTest(
            AiHeuristics.AiPlayCandidate immediateBest,
            AiHeuristics.AiPlayCandidate rolloutBest) {
        return rolloutOverrideTransitionAllowed(immediateBest, rolloutBest);
    }

    static double defaultStealCompletionGainMultiplierForTest() {
        return STEAL_COMPLETION_GAIN_MULTIPLIER;
    }

    static int defaultStructuredTacticalReservedCandidatesForTest() {
        return STRUCTURED_TACTICAL_RESERVED_CANDIDATES;
    }

    static double defaultStructuredPrunePriorityWeightForTest() {
        return STRUCTURED_PRUNE_PRIORITY_WEIGHT;
    }

    static boolean defaultBoardAwareOverflowDiscardForTest() {
        return BOARD_AWARE_OVERFLOW_DISCARD;
    }

    static double defaultWildOverfullSetPenaltyForTest() {
        return WILD_OVERFULL_SET_PENALTY;
    }

    static int defaultResponseTenantThresholdForTest() {
        return RESPONSE_TENANT_THRESHOLD;
    }

    static int defaultResponseCounterThresholdForTest() {
        return RESPONSE_COUNTER_THRESHOLD;
    }

    static double candidateAdjustmentForTest(AiHeuristics.AiPlayCandidate candidate) {
        return candidateAdjustment(null, candidate);
    }

    static String candidateEffectForTest(AiHeuristics.AiPlayCandidate candidate) {
        return candidateEffect(null, candidate);
    }

    static double wildDeployAdjustmentForTest(
            Player actor,
            PlayActionRequest request,
            double wildDeployPenalty,
            double wildShortSetPenalty,
            double wildCompletionPenalty,
            double wildOverfullSetPenalty) {
        return wildDeployAdjustment(
                actor,
                request,
                wildDeployPenalty,
                wildShortSetPenalty,
                wildCompletionPenalty,
                wildOverfullSetPenalty);
    }

    static double overflowDiscardRetentionScoreForTest(AIPlayer bot, Card card) {
        return overflowDiscardRetentionScore(bot, card);
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

    private static String winnerFromLastActionSummary(GameStateSnapshot snapshot) {
        String summary = trim(snapshot.getLastActionSummary()).toLowerCase(Locale.ROOT);
        if (summary.isBlank()) {
            return null;
        }
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            String displayName = trim(player.getDisplayName()).toLowerCase(Locale.ROOT);
            String playerId = trim(player.getPlayerId()).toLowerCase(Locale.ROOT);
            if (!displayName.isBlank() && summary.startsWith(displayName + " wins")) {
                return player.getPlayerId();
            }
            if (!playerId.isBlank() && summary.startsWith(playerId + " wins")) {
                return player.getPlayerId();
            }
        }
        return null;
    }

    private static String requestKey(PlayActionRequest req) {
        if (req == null) {
            return "";
        }
        return String.join("|",
                trim(req.getActionType()).toUpperCase(Locale.ROOT),
                trim(req.getCardId()),
                trim(req.getTargetPlayerId()),
                trim(req.getTargetColorKey()).toUpperCase(Locale.ROOT),
                trim(req.getTargetCardId()),
                trim(req.getActorCardId()),
                trim(req.getTargetZone()).toUpperCase(Locale.ROOT));
    }

    private static boolean sameEffectHardTargetFallbackUsed(
            ScoredCandidate modelBest,
            ScoredCandidate hardChoice,
            ScoredCandidate currentChoice) {
        return modelBest != null
                && modelBest.candidate() != null
                && hardChoice != null
                && hardChoice.candidate() != null
                && currentChoice != null
                && currentChoice.candidate() != null
                && currentChoice.candidate().id().equals(hardChoice.candidate().id())
                && !modelBest.candidate().id().equals(hardChoice.candidate().id())
                && sameEffectDifferentTarget(modelBest.candidate(), hardChoice.candidate());
    }

    private static boolean hardRentFallbackUsed(
            ScoredCandidate modelBest,
            ScoredCandidate hardChoice,
            ScoredCandidate currentChoice) {
        if (modelBest == null
                || modelBest.candidate() == null
                || hardChoice == null
                || hardChoice.candidate() == null
                || currentChoice == null
                || currentChoice.candidate() == null
                || !currentChoice.candidate().id().equals(hardChoice.candidate().id())
                || modelBest.candidate().id().equals(hardChoice.candidate().id())) {
            return false;
        }
        String hardEffect = candidateEffect(null, hardChoice.candidate());
        String modelEffect = candidateEffect(null, modelBest.candidate());
        return ("RENT".equals(hardEffect) || "RENT_DUAL".equals(hardEffect))
                && ("DEPLOY".equals(modelEffect) || "PASS_GO".equals(modelEffect));
    }

    private static boolean sameEffectDifferentTarget(
            AiHeuristics.AiPlayCandidate a,
            AiHeuristics.AiPlayCandidate b) {
        if (a == null || b == null || a.request() == null || b.request() == null) {
            return false;
        }
        String effect = candidateEffect(null, a);
        if (effect.isBlank()
                || !effect.equals(candidateEffect(null, b))
                || !SAME_EFFECT_HARD_TARGET_EFFECTS.contains(effect)) {
            return false;
        }
        PlayActionRequest left = a.request();
        PlayActionRequest right = b.request();
        if (!trim(left.getActionType()).equalsIgnoreCase(trim(right.getActionType()))
                || !trim(left.getCardId()).equals(trim(right.getCardId()))) {
            return false;
        }
        boolean targetDiffers = !trim(left.getTargetPlayerId()).equals(trim(right.getTargetPlayerId()))
                || !trim(left.getTargetColorKey()).equalsIgnoreCase(trim(right.getTargetColorKey()))
                || !trim(left.getTargetCardId()).equals(trim(right.getTargetCardId()))
                || !trim(left.getActorCardId()).equals(trim(right.getActorCardId()))
                || !trim(left.getTargetZone()).equalsIgnoreCase(trim(right.getTargetZone()));
        return targetDiffers;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static Set<String> parseEffectSet(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        for (String part : raw.split(",")) {
            String value = part.trim().toUpperCase(Locale.ROOT);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return Set.copyOf(out);
    }

    private static String effectSetString(Set<String> effects) {
        if (effects == null || effects.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>(effects);
        out.sort(String::compareTo);
        return String.join(",", out);
    }

    private static Player playerById(GameController controller, String playerId) {
        if (controller == null || playerId == null || playerId.isBlank()) {
            return null;
        }
        for (Player player : controller.getSessionPlayersView()) {
            if (playerId.equals(player.getPlayerId())) {
                return player;
            }
        }
        return null;
    }

    private static boolean requestMayRevealHiddenCards(Player actor, PlayActionRequest request) {
        if (actor == null || request == null
                || !"ACTION".equalsIgnoreCase(trim(request.getActionType()))) {
            return false;
        }
        String cardId = trim(request.getCardId());
        if (cardId.isBlank()) {
            return false;
        }
        for (Card card : actor.getHandCardsView()) {
            if (cardId.equals(card.getId()) && card instanceof ActionCard actionCard) {
                return "PASS_GO".equalsIgnoreCase(trim(actionCard.getEffectCode()));
            }
        }
        return false;
    }

    private static double candidateAdjustment(
            Player actor,
            AiHeuristics.AiPlayCandidate candidate) {
        if (candidate == null || candidate.request() == null) {
            return 0d;
        }
        double wildDeployAdjustment = wildDeployAdjustment(actor, candidate.request());
        if (wildDeployAdjustment != 0d) {
            return wildDeployAdjustment;
        }
        String actionType = trim(candidate.request().getActionType()).toUpperCase(Locale.ROOT);
        boolean deposit = "DEPOSIT".equals(actionType);
        String effect = candidateEffect(actor, candidate);
        if (deposit && "PASS_GO".equals(effect)) {
            return PASS_GO_DEPOSIT_BONUS;
        }
        if (!deposit && "PASS_GO".equals(effect)) {
            int handCount = actor == null ? 0 : actor.getHandCardCount();
            int overflow = Math.max(0, handCount + 2 - 7);
            return PASS_GO_EXPECTED_VALUE - overflow * PASS_GO_OVERFLOW_PENALTY;
        }
        if (!deposit && "BIRTHDAY".equals(effect) && LOW_BIRTHDAY_PENALTY != 0d) {
            int expectedPaid = parseExpectedPaid(candidate.summary());
            if (expectedPaid > 0 && expectedPaid <= 2) {
                return LOW_BIRTHDAY_PENALTY;
            }
        }
        if (!deposit && "BIRTHDAY".equals(effect) && BIRTHDAY_EXPECTED_PAID_MULTIPLIER != 0d) {
            return parseExpectedPaid(candidate.summary()) * BIRTHDAY_EXPECTED_PAID_MULTIPLIER;
        }
        if (!deposit && ("RENT".equals(effect) || "RENT_DUAL".equals(effect))) {
            return RENT_ACTION_BONUS + parseExpectedPaid(candidate.summary()) * RENT_EXPECTED_PAID_MULTIPLIER;
        }
        if (!deposit && "DEBT_COLLECTOR".equals(effect) && DEBT_EXPECTED_PAID_MULTIPLIER != 0d) {
            return parseExpectedPaid(candidate.summary()) * DEBT_EXPECTED_PAID_MULTIPLIER;
        }
        if (!deposit && "STEAL_PROPERTY".equals(effect)) {
            return STEAL_PROPERTY_BONUS
                    + numberAfter(candidate.summary(), "completionGain=") * STEAL_COMPLETION_GAIN_MULTIPLIER
                    + numberAfter(candidate.summary(), "oppCompletionLoss=") * STEAL_OPP_COMPLETION_LOSS_MULTIPLIER
                    + numberAfter(candidate.summary(), "takeValue=") * STEAL_TAKE_VALUE_MULTIPLIER
                    + (containsMarkerValue(candidate.summary(), "wild=", "true") ? STEAL_WILD_BONUS : 0d);
        }
        if (!deposit && "FORCED_DEAL".equals(effect)) {
            return FORCED_DEAL_BONUS;
        }
        if (!deposit && ("HOUSE".equals(effect) || "HOTEL".equals(effect))) {
            return BUILDING_ACTION_BONUS;
        }
        return 0d;
    }

    private static double wildDeployAdjustment(Player actor, PlayActionRequest request) {
        return wildDeployAdjustment(
                actor,
                request,
                WILD_DEPLOY_PENALTY,
                WILD_SHORT_SET_PENALTY,
                WILD_COMPLETION_PENALTY,
                WILD_OVERFULL_SET_PENALTY);
    }

    private static double wildDeployAdjustment(
            Player actor,
            PlayActionRequest request,
            double wildDeployPenalty,
            double wildShortSetPenalty,
            double wildCompletionPenalty,
            double wildOverfullSetPenalty) {
        if (actor == null || request == null
                || !"DEPLOY".equalsIgnoreCase(trim(request.getActionType()))) {
            return 0d;
        }
        String cardId = trim(request.getCardId());
        String color = trim(request.getTargetColorKey()).toUpperCase(Locale.ROOT);
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

    private static String candidateEffect(
            Player actor,
            AiHeuristics.AiPlayCandidate candidate) {
        String fromSummary = effectFromSummary(candidate == null ? "" : candidate.summary());
        if (!fromSummary.isBlank()) {
            return fromSummary;
        }
        return candidate == null ? "" : actionEffect(actor, candidate.request());
    }

    private static String effectFromSummary(String summary) {
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

    private static String actionEffect(Player actor, PlayActionRequest request) {
        if (actor == null || request == null
                || !"ACTION".equalsIgnoreCase(trim(request.getActionType()))) {
            return "";
        }
        String cardId = trim(request.getCardId());
        if (cardId.isBlank()) {
            return "";
        }
        for (Card card : actor.getHandCardsView()) {
            if (cardId.equals(card.getId()) && card instanceof ActionCard actionCard) {
                return trim(actionCard.getEffectCode()).toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static int parseExpectedPaid(String summary) {
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

    private static int numberAfter(String summary, String marker) {
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

    private static boolean containsMarkerValue(String summary, String marker, String expected) {
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

    private record ScoredCandidate(
            AiHeuristics.AiPlayCandidate candidate,
            double score) {
    }

    private record CorrectedChoice(
            ScoredCandidate chosen,
            JsonObject metadata) {
    }

    private record ThreatProfile(
            double value,
            int completeSets,
            int nearCompleteColors) {
    }

    private record CandidatePriority(
            AiHeuristics.AiPlayCandidate candidate,
            int priority) {
    }

    private enum RolloutStep {
        CONTINUE,
        STOP_AFTER_HIDDEN_DRAW,
        NO_PLAY
    }

    private static final class BlindHardRolloutStrategy implements AiPlayStrategy, AiChoiceAdvisor {
        private final HardAiPlayStrategy hard = new HardAiPlayStrategy();

        @Override
        public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
            return hard.tryPlayOneCard(bot, context, bridge);
        }

        @Override
        public AiHeuristics.AiResponseDecision chooseResponse(
                AIPlayer bot,
                GameContext context,
                boolean counterRole) {
            return AiHeuristics.AiResponseDecision.pass();
        }

        @Override
        public PaymentSettlement.PaymentChoice choosePayment(
                AIPlayer bot,
                GameContext context,
                Player creditor,
                int amountDue,
                PaymentSettlement.PaymentChoice fallbackChoice) {
            return fallbackChoice;
        }

        @Override
        public List<Card> chooseOverflowDiscards(
                AIPlayer bot,
                GameContext context,
                int limit,
                List<Card> fallbackCards) {
            return fallbackCards;
        }
    }

    private static double overflowDiscardRetentionScore(AIPlayer bot, Card card) {
        if (card == null) {
            return -1d;
        }
        if (card instanceof PropertyWildCard wild) {
            return wild.getWildPropertyKind() == PropertyWildCard.WildPropertyKind.ANY_COLOR
                    ? 1_050d
                    : 920d + PayableCards.valueOf(wild) * 12d;
        }
        if (card instanceof PropertyCard property) {
            return propertyOverflowRetentionScore(bot, property);
        }
        if (card instanceof ActionCard action) {
            return actionOverflowRetentionScore(action);
        }
        return PayableCards.valueOf(card) * 10d;
    }

    private static double propertyOverflowRetentionScore(AIPlayer bot, PropertyCard property) {
        String color = trim(property.getColorGroup()).toUpperCase(Locale.ROOT);
        int value = PayableCards.valueOf(property);
        if (bot == null || color.isBlank()) {
            return 520d + value * 20d;
        }
        int need = Math.max(1, PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3));
        int effective = PropertySetCalculator.effectiveCountForColor(bot.getPropertyCardsView(), color);
        int after = effective + 1;
        if (after >= need) {
            return 1_120d + value * 18d;
        }
        if (after == need - 1) {
            return 820d + value * 18d;
        }
        if (after == 1) {
            return 390d + value * 18d;
        }
        return 560d + value * 18d;
    }

    private static double actionOverflowRetentionScore(ActionCard action) {
        String effect = trim(action.getEffectCode()).toUpperCase(Locale.ROOT);
        int value = PayableCards.valueOf(action);
        double base = switch (effect) {
            case "DEAL_BREAKER" -> 1_080d;
            case "RENT_WAIVER" -> 980d;
            case "STEAL_PROPERTY", "FORCED_DEAL" -> 930d;
            case "PASS_GO" -> 760d;
            case "HOUSE", "HOTEL" -> 735d;
            case "DEBT_COLLECTOR", "BIRTHDAY" -> 650d;
            case "DOUBLE_RENT", "RENT", "RENT_DUAL" -> 610d;
            default -> 520d;
        };
        return base + value * 10d;
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
                    && "RENT_WAIVER".equalsIgnoreCase(trim(action.getEffectCode()))) {
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

    private static final class RecordingBridge implements AiGameBridge {
        private PlayActionRequest request;

        @Override
        public void submitPlayAction(PlayActionRequest request) {
            this.request = request;
        }
    }

    private static final class CapturingSubject implements GameUpdateSubject {
        private final AtomicReference<GameStateSnapshot> last = new AtomicReference<>();

        @Override
        public void registerObserver(GameUpdateObserver observer) {
        }

        @Override
        public void unregisterObserver(GameUpdateObserver observer) {
        }

        @Override
        public void notifyStateChanged(GameStateSnapshot snapshot) {
            last.set(snapshot);
        }

        private GameStateSnapshot last() {
            return last.get();
        }
    }
}
