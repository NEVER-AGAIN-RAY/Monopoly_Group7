package com.monopoly.pattern.strategy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchConfigSnapshotTest {

    @Test
    void effectiveConfigSnapshotMatchesBaseline() {
        JsonObject snapshot = SearchLookaheadAiPlayStrategy.effectiveConfigSnapshot();
        assertEquals(16, snapshot.get("monopoly.search.maxCandidates").getAsInt());
        assertEquals(0, snapshot.get("monopoly.search.tacticalReservedCandidates").getAsInt());
        assertEquals(0, snapshot.get("monopoly.search.structuredTacticalReservedCandidates").getAsInt());
        assertEquals(0d, snapshot.get("monopoly.search.structuredPrunePriorityWeight").getAsDouble());
        assertEquals(false, snapshot.get("monopoly.search.rolloutRemainingTurn").getAsBoolean());
        assertEquals(0d, snapshot.get("monopoly.search.rolloutOverrideMargin").getAsDouble());
        assertEquals("", snapshot.get("monopoly.search.rolloutOverrideAllowedEffects").getAsString());
        assertEquals("", snapshot.get("monopoly.search.rolloutOverrideAllowedTransitions").getAsString());
        assertEquals("hard", snapshot.get("monopoly.search.rolloutPolicy").getAsString());
        assertEquals(8, snapshot.get("monopoly.search.rolloutMaxCandidates").getAsInt());
        assertEquals(false, snapshot.get("monopoly.search.rolloutNextOpponentTurn").getAsBoolean());
        assertEquals(0d, snapshot.get("monopoly.search.hardMargin").getAsDouble());
        assertEquals(0.92d, snapshot.get("monopoly.search.maxOpponentWeight").getAsDouble());
        assertEquals(0.12d, snapshot.get("monopoly.search.avgOpponentWeight").getAsDouble());
        assertEquals(1.0d, snapshot.get("monopoly.search.threatWeight").getAsDouble());
        assertEquals(13000d, snapshot.get("monopoly.search.completeSetValue").getAsDouble());
        assertEquals(1000d, snapshot.get("monopoly.search.nearCompleteValue").getAsDouble());
        assertEquals(680d, snapshot.get("monopoly.search.cappedProgressValue").getAsDouble());
        assertEquals(120d, snapshot.get("monopoly.search.propertyCountValue").getAsDouble());
        assertEquals(35d, snapshot.get("monopoly.search.bankValue").getAsDouble());
        assertEquals(320d, snapshot.get("monopoly.search.buildingRentBonusValue").getAsDouble());
        assertEquals(260d, snapshot.get("monopoly.search.opponentBuildingThreatValue").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.conditionalThreatWeight").getAsDouble());
        assertEquals(1, snapshot.get("monopoly.search.conditionalThreatSelfMaxSets").getAsInt());
        assertEquals(2, snapshot.get("monopoly.search.conditionalThreatOpponentMinSets").getAsInt());
        assertEquals(2, snapshot.get("monopoly.search.conditionalThreatOpponentMinAlmost").getAsInt());
        assertEquals(4000d, snapshot.get("monopoly.search.passGoExpectedValue").getAsDouble());
        assertEquals(180d, snapshot.get("monopoly.search.passGoOverflowPenalty").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.passGoDepositBonus").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.lowBirthdayPenalty").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.birthdayExpectedPaidMultiplier").getAsDouble());
        assertEquals(600d, snapshot.get("monopoly.search.rentExpectedPaidMultiplier").getAsDouble());
        assertEquals(300d, snapshot.get("monopoly.search.debtExpectedPaidMultiplier").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.stealPropertyBonus").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.stealCompletionGainMultiplier").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.stealOppCompletionLossMultiplier").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.stealTakeValueMultiplier").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.stealWildBonus").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.forcedDealBonus").getAsDouble());
        assertEquals(900d, snapshot.get("monopoly.search.buildingActionBonus").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.rentActionBonus").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.wildDeployPenalty").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.wildShortSetPenalty").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.wildCompletionPenalty").getAsDouble());
        assertEquals(0d, snapshot.get("monopoly.search.wildOverfullSetPenalty").getAsDouble());
        assertEquals(true, snapshot.get("monopoly.search.boardAwareOverflowDiscard").getAsBoolean());
        assertEquals(true, snapshot.get("monopoly.search.boardAwarePayment").getAsBoolean());
        assertEquals(4d, snapshot.get("monopoly.search.paymentCompleteSetBreakPenalty").getAsDouble());
        assertEquals(1.5d, snapshot.get("monopoly.search.paymentNearSetBreakPenalty").getAsDouble());
        assertEquals(512, snapshot.get("monopoly.search.maxPaymentCandidates").getAsInt());
        assertEquals(3, snapshot.get("monopoly.search.responseTenantThreshold").getAsInt());
        assertEquals(5, snapshot.get("monopoly.search.responseCounterThreshold").getAsInt());
        assertEquals(-1d, snapshot.get("monopoly.search.sameEffectHardTargetMargin").getAsDouble());
        assertEquals(-1d, snapshot.get("monopoly.search.hardRentFallbackMargin").getAsDouble());
        assertEquals("FORCED_DEAL,STEAL_PROPERTY", snapshot.get("monopoly.search.sameEffectHardTargetEffects").getAsString());
        assertEquals("", snapshot.get("monopoly.search.correctorModelPath").getAsString());
        assertEquals(0.6d, snapshot.get("monopoly.search.correctorMinScoreGap").getAsDouble());
        assertEquals(900d, snapshot.get("monopoly.search.correctorMaxLookaheadGap").getAsDouble());
        assertEquals(false, snapshot.get("monopoly.search.correctorAllowLowerLookaheadScore").getAsBoolean());
        assertEquals(false, snapshot.get("monopoly.search.trace.includeMemento").getAsBoolean());
        assertEquals("DEAL_BREAKER,DEBT_COLLECTOR,FORCED_DEAL,HOTEL,HOUSE,PASS_GO,RENT,RENT_DUAL,STEAL_PROPERTY",
                snapshot.get("monopoly.search.correctorAllowedEffects").getAsString());
        assertEquals("ACTION", snapshot.get("monopoly.search.correctorAllowedActionTypes").getAsString());
    }
}