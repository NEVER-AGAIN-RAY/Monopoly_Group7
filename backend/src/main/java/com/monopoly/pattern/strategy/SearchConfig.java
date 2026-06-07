package com.monopoly.pattern.strategy;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SearchConfig {

    private SearchConfig() {
    }

    static final int MAX_CANDIDATES =
            Integer.getInteger("monopoly.search.maxCandidates", 16);
    static final int TACTICAL_RESERVED_CANDIDATES =
            Integer.getInteger("monopoly.search.tacticalReservedCandidates", 0);
    static final int STRUCTURED_TACTICAL_RESERVED_CANDIDATES =
            Integer.getInteger("monopoly.search.structuredTacticalReservedCandidates", 0);
    static final double STRUCTURED_PRUNE_PRIORITY_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.structuredPrunePriorityWeight", "0"));
    static final boolean ROLLOUT_REMAINING_TURN =
            Boolean.parseBoolean(System.getProperty("monopoly.search.rolloutRemainingTurn", "false"));
    static final double ROLLOUT_OVERRIDE_MARGIN =
            Double.parseDouble(System.getProperty("monopoly.search.rolloutOverrideMargin", "0"));
    static final Set<String> ROLLOUT_OVERRIDE_ALLOWED_EFFECTS = parseEffectSet(
            System.getProperty("monopoly.search.rolloutOverrideAllowedEffects", ""));
    static final Set<String> ROLLOUT_OVERRIDE_ALLOWED_TRANSITIONS = parseEffectSet(
            System.getProperty("monopoly.search.rolloutOverrideAllowedTransitions", ""));
    static final String ROLLOUT_POLICY =
            System.getProperty("monopoly.search.rolloutPolicy", "hard")
                    .trim()
                    .toLowerCase(Locale.ROOT);
    static final int MAX_ROLLOUT_CANDIDATES =
            Integer.getInteger("monopoly.search.rolloutMaxCandidates", 8);
    static final boolean ROLLOUT_NEXT_OPPONENT_TURN =
            Boolean.parseBoolean(System.getProperty("monopoly.search.rolloutNextOpponentTurn", "false"));
    static final double HARD_MARGIN =
            Double.parseDouble(System.getProperty("monopoly.search.hardMargin", "0"));
    static final double MAX_OPPONENT_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.maxOpponentWeight", "0.92"));
    static final double AVG_OPPONENT_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.avgOpponentWeight", "0.12"));
    static final double THREAT_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.threatWeight", "1.0"));
    static final double COMPLETE_SET_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.completeSetValue", "13000"));
    static final double NEAR_COMPLETE_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.nearCompleteValue", "1000"));
    static final double CAPPED_PROGRESS_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.cappedProgressValue", "680"));
    static final double PROPERTY_COUNT_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.propertyCountValue", "120"));
    static final double BANK_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.bankValue", "35"));
    static final double BUILDING_RENT_BONUS_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.buildingRentBonusValue", "320"));
    static final double OPPONENT_BUILDING_THREAT_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.opponentBuildingThreatValue", "260"));
    static final double CONDITIONAL_THREAT_WEIGHT =
            Double.parseDouble(System.getProperty("monopoly.search.conditionalThreatWeight", "0"));
    static final int CONDITIONAL_THREAT_SELF_MAX_SETS =
            Integer.getInteger("monopoly.search.conditionalThreatSelfMaxSets", 1);
    static final int CONDITIONAL_THREAT_OPPONENT_MIN_SETS =
            Integer.getInteger("monopoly.search.conditionalThreatOpponentMinSets", 2);
    static final int CONDITIONAL_THREAT_OPPONENT_MIN_ALMOST =
            Integer.getInteger("monopoly.search.conditionalThreatOpponentMinAlmost", 2);
    static final double PASS_GO_EXPECTED_VALUE =
            Double.parseDouble(System.getProperty("monopoly.search.passGoExpectedValue", "4000"));
    static final double PASS_GO_OVERFLOW_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.passGoOverflowPenalty", "180"));
    static final double PASS_GO_DEPOSIT_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.passGoDepositBonus", "0"));
    static final double LOW_BIRTHDAY_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.lowBirthdayPenalty", "0"));
    static final double BIRTHDAY_EXPECTED_PAID_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.birthdayExpectedPaidMultiplier", "0"));
    static final double RENT_EXPECTED_PAID_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.rentExpectedPaidMultiplier", "600"));
    static final double DEBT_EXPECTED_PAID_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.debtExpectedPaidMultiplier", "300"));
    static final double STEAL_PROPERTY_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.stealPropertyBonus", "0"));
    static final double STEAL_COMPLETION_GAIN_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.stealCompletionGainMultiplier", "0"));
    static final double STEAL_OPP_COMPLETION_LOSS_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.stealOppCompletionLossMultiplier", "0"));
    static final double STEAL_TAKE_VALUE_MULTIPLIER =
            Double.parseDouble(System.getProperty("monopoly.search.stealTakeValueMultiplier", "0"));
    static final double STEAL_WILD_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.stealWildBonus", "0"));
    static final double FORCED_DEAL_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.forcedDealBonus", "0"));
    static final double BUILDING_ACTION_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.buildingActionBonus", "900"));
    static final double RENT_ACTION_BONUS =
            Double.parseDouble(System.getProperty("monopoly.search.rentActionBonus", "0"));
    static final double WILD_DEPLOY_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.wildDeployPenalty", "0"));
    static final double WILD_SHORT_SET_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.wildShortSetPenalty", "0"));
    static final double WILD_COMPLETION_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.wildCompletionPenalty", "0"));
    static final double WILD_OVERFULL_SET_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.wildOverfullSetPenalty", "0"));
    static final boolean BOARD_AWARE_OVERFLOW_DISCARD =
            Boolean.parseBoolean(System.getProperty("monopoly.search.boardAwareOverflowDiscard", "true"));
    static final boolean BOARD_AWARE_PAYMENT =
            Boolean.parseBoolean(System.getProperty("monopoly.search.boardAwarePayment", "true"));
    static final double PAYMENT_COMPLETE_SET_BREAK_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.paymentCompleteSetBreakPenalty", "4"));
    static final double PAYMENT_NEAR_SET_BREAK_PENALTY =
            Double.parseDouble(System.getProperty("monopoly.search.paymentNearSetBreakPenalty", "1.5"));
    static final int MAX_PAYMENT_CANDIDATES =
            Integer.getInteger("monopoly.search.maxPaymentCandidates", 512);
    static final int RESPONSE_TENANT_THRESHOLD =
            Integer.getInteger("monopoly.search.responseTenantThreshold", 3);
    static final int RESPONSE_COUNTER_THRESHOLD =
            Integer.getInteger("monopoly.search.responseCounterThreshold", 5);
    static final double SAME_EFFECT_HARD_TARGET_MARGIN =
            Double.parseDouble(System.getProperty("monopoly.search.sameEffectHardTargetMargin", "-1"));
    static final double HARD_RENT_FALLBACK_MARGIN =
            Double.parseDouble(System.getProperty("monopoly.search.hardRentFallbackMargin", "-1"));
    static final Set<String> SAME_EFFECT_HARD_TARGET_EFFECTS = parseEffectSet(
            System.getProperty(
                    "monopoly.search.sameEffectHardTargetEffects",
                    "STEAL_PROPERTY,FORCED_DEAL"));
    static final String CORRECTOR_MODEL_PATH =
            System.getProperty("monopoly.search.correctorModelPath", "").trim();
    static final double CORRECTOR_MIN_SCORE_GAP =
            Double.parseDouble(System.getProperty("monopoly.search.correctorMinScoreGap", "0.6"));
    static final double CORRECTOR_MAX_LOOKAHEAD_GAP =
            Double.parseDouble(System.getProperty("monopoly.search.correctorMaxLookaheadGap", "900"));
    static final boolean CORRECTOR_ALLOW_LOWER_LOOKAHEAD_SCORE =
            Boolean.parseBoolean(System.getProperty(
                    "monopoly.search.correctorAllowLowerLookaheadScore",
                    "false"));
    static final boolean TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO =
            Boolean.parseBoolean(System.getProperty(
                    "monopoly.search.trace.includeMemento",
                    "false"));
    static final Set<String> CORRECTOR_ALLOWED_EFFECTS = parseEffectSet(
            System.getProperty(
                    "monopoly.search.correctorAllowedEffects",
                    "RENT,RENT_DUAL,HOUSE,HOTEL,DEBT_COLLECTOR,STEAL_PROPERTY,FORCED_DEAL,DEAL_BREAKER,PASS_GO"));
    static final Set<String> CORRECTOR_ALLOWED_ACTION_TYPES = parseEffectSet(
            System.getProperty("monopoly.search.correctorAllowedActionTypes", "ACTION"));

    static JsonObject effectiveConfigSnapshot() {
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

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static Set<String> parseEffectSet(String raw) {
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

    static String effectSetString(Set<String> effects) {
        if (effects == null || effects.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>(effects);
        out.sort(String::compareTo);
        return String.join(",", out);
    }
}