package com.monopoly.pattern.strategy;

import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.simulation.DecisionTraceSink;
import com.monopoly.simulation.SimulationDecisionRequest;
import com.monopoly.simulation.SimulationDecisionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchLookaheadAiPlayStrategyTest {

    @Test
    void defaultBuildingParametersUsePromotedNaturalWinRateCandidate() {
        assertEquals(900d, SearchLookaheadAiPlayStrategy.defaultBuildingActionBonusForTest());
        assertEquals(320d, SearchLookaheadAiPlayStrategy.defaultBuildingRentBonusValueForTest());
        assertEquals(260d, SearchLookaheadAiPlayStrategy.defaultOpponentBuildingThreatValueForTest());
        assertEquals(0d, SearchLookaheadAiPlayStrategy.defaultPassGoDepositBonusForTest());
        assertEquals(-1d, SearchLookaheadAiPlayStrategy.defaultSameEffectHardTargetMarginForTest());
        assertEquals(-1d, SearchLookaheadAiPlayStrategy.defaultHardRentFallbackMarginForTest());
        assertEquals(0d, SearchLookaheadAiPlayStrategy.defaultRolloutOverrideMarginForTest());
        assertEquals(0, SearchLookaheadAiPlayStrategy.defaultRolloutOverrideAllowedEffectsForTest());
        assertEquals(0, SearchLookaheadAiPlayStrategy.defaultRolloutOverrideAllowedTransitionsForTest());
        assertEquals(0d, SearchLookaheadAiPlayStrategy.defaultStealCompletionGainMultiplierForTest());
        assertEquals(0, SearchLookaheadAiPlayStrategy.defaultStructuredTacticalReservedCandidatesForTest());
        assertEquals(0d, SearchLookaheadAiPlayStrategy.defaultStructuredPrunePriorityWeightForTest());
        assertEquals(true, SearchLookaheadAiPlayStrategy.defaultBoardAwareOverflowDiscardForTest());
        assertEquals(true, SearchLookaheadAiPlayStrategy.defaultBoardAwarePaymentForTest());
        assertEquals(0d, SearchLookaheadAiPlayStrategy.defaultWildOverfullSetPenaltyForTest());
        assertEquals(3, SearchLookaheadAiPlayStrategy.defaultResponseTenantThresholdForTest());
        assertEquals(5, SearchLookaheadAiPlayStrategy.defaultResponseCounterThresholdForTest());
    }

    @Test
    void naturalWinnerUsesUniqueCompleteSetWinner() {
        GameStateSnapshot snapshot = gameOverSnapshot();
        snapshot.addPlayerSummary("p1", "AI-Lookahead-1", 1, 0, 10, 0, 3);
        snapshot.addPlayerSummary("p2", "AI-Hard-2", 1, 0, 9, 0, 2);

        assertEquals("p1", SearchLookaheadAiPlayStrategy.naturalWinnerId(snapshot));
    }

    @Test
    void naturalWinnerUsesLastActionSummaryWhenFinalSnapshotHasMultipleCompleteSetPlayers() {
        GameStateSnapshot snapshot = gameOverSnapshot();
        snapshot.setLastActionSummary("AI-Hard-2 wins (3 complete property sets).");
        snapshot.addPlayerSummary("p1", "AI-Lookahead-1", 1, 0, 12, 0, 3);
        snapshot.addPlayerSummary("p2", "AI-Hard-2", 1, 0, 11, 0, 3);

        assertEquals("p2", SearchLookaheadAiPlayStrategy.naturalWinnerId(snapshot));
    }

    @Test
    void naturalWinnerRemainsUnknownWhenAmbiguousSnapshotHasNoMatchingSummary() {
        GameStateSnapshot snapshot = gameOverSnapshot();
        snapshot.setLastActionSummary("Game ended.");
        snapshot.addPlayerSummary("p1", "AI-Lookahead-1", 1, 0, 12, 0, 3);
        snapshot.addPlayerSummary("p2", "AI-Hard-2", 1, 0, 11, 0, 3);

        assertNull(SearchLookaheadAiPlayStrategy.naturalWinnerId(snapshot));
    }

    @Test
    void depositedPassGoDoesNotReceivePlayPassGoAdjustmentByDefault() {
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType("DEPOSIT");
        request.setCardId("pass_go_1");
        AiHeuristics.AiPlayCandidate candidate = new AiHeuristics.AiPlayCandidate(
                "c1",
                request,
                "Deposit action card PASS_GO for 1M.");

        assertEquals(0d, SearchLookaheadAiPlayStrategy.candidateAdjustmentForTest(candidate));
    }

    @Test
    void candidateEffectRecognizesDeploySummaryForGates() {
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType("DEPLOY");
        request.setCardId("prop_1");
        AiHeuristics.AiPlayCandidate candidate = new AiHeuristics.AiPlayCandidate(
                "c1",
                request,
                "Deploy property GREEN completionScore=66.");

        assertEquals("DEPLOY", SearchLookaheadAiPlayStrategy.candidateEffectForTest(candidate));
    }

    @Test
    void candidateEffectRecognizesGenericDepositSummaryForGates() {
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType("DEPOSIT");
        request.setCardId("money_1");
        AiHeuristics.AiPlayCandidate candidate = new AiHeuristics.AiPlayCandidate(
                "c1",
                request,
                "Deposit money/bankable card for 3M.");

        assertEquals("DEPOSIT", SearchLookaheadAiPlayStrategy.candidateEffectForTest(candidate));
    }

    @Test
    void candidateEffectKeepsActionCardDepositEffectForGates() {
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType("DEPOSIT");
        request.setCardId("pass_go_1");
        AiHeuristics.AiPlayCandidate candidate = new AiHeuristics.AiPlayCandidate(
                "c1",
                request,
                "Deposit action card PASS_GO for 1M.");

        assertEquals("PASS_GO", SearchLookaheadAiPlayStrategy.candidateEffectForTest(candidate));
    }

    @Test
    void rolloutTransitionGateAllowsAllTransitionsByDefault() {
        PlayActionRequest immediateRequest = new PlayActionRequest();
        immediateRequest.setActionType("ACTION");
        immediateRequest.setCardId("pass_go_1");
        AiHeuristics.AiPlayCandidate immediate = new AiHeuristics.AiPlayCandidate(
                "c1",
                immediateRequest,
                "Action PASS_GO.");
        PlayActionRequest rolloutRequest = new PlayActionRequest();
        rolloutRequest.setActionType("DEPLOY");
        rolloutRequest.setCardId("prop_1");
        AiHeuristics.AiPlayCandidate rollout = new AiHeuristics.AiPlayCandidate(
                "c2",
                rolloutRequest,
                "Deploy property GREEN completionScore=66.");

        assertTrue(SearchLookaheadAiPlayStrategy.rolloutOverrideTransitionAllowedForTest(immediate, rollout));
    }

    @Test
    void sameEffectDifferentTargetRecognizesStealTargetChoice() {
        PlayActionRequest left = new PlayActionRequest();
        left.setActionType("ACTION");
        left.setCardId("sly_deal_1");
        left.setTargetPlayerId("p2");
        left.setTargetCardId("prop_1");
        left.setTargetZone("PROPERTY");
        AiHeuristics.AiPlayCandidate source = new AiHeuristics.AiPlayCandidate(
                "c1",
                left,
                "Action STEAL_PROPERTY target=p2 card=prop_1.");

        PlayActionRequest right = new PlayActionRequest();
        right.setActionType("ACTION");
        right.setCardId("sly_deal_1");
        right.setTargetPlayerId("p2");
        right.setTargetCardId("prop_2");
        right.setTargetZone("PROPERTY");
        AiHeuristics.AiPlayCandidate hard = new AiHeuristics.AiPlayCandidate(
                "c2",
                right,
                "Action STEAL_PROPERTY target=p2 card=prop_2.");

        assertEquals(true, SearchLookaheadAiPlayStrategy.sameEffectDifferentTargetForTest(source, hard));
    }

    @Test
    void sameEffectDifferentTargetRejectsDifferentCards() {
        PlayActionRequest left = new PlayActionRequest();
        left.setActionType("ACTION");
        left.setCardId("sly_deal_1");
        left.setTargetPlayerId("p2");
        left.setTargetCardId("prop_1");
        AiHeuristics.AiPlayCandidate source = new AiHeuristics.AiPlayCandidate(
                "c1",
                left,
                "Action STEAL_PROPERTY target=p2 card=prop_1.");

        PlayActionRequest right = new PlayActionRequest();
        right.setActionType("ACTION");
        right.setCardId("sly_deal_2");
        right.setTargetPlayerId("p2");
        right.setTargetCardId("prop_2");
        AiHeuristics.AiPlayCandidate otherCard = new AiHeuristics.AiPlayCandidate(
                "c2",
                right,
                "Action STEAL_PROPERTY target=p2 card=prop_2.");

        assertEquals(false, SearchLookaheadAiPlayStrategy.sameEffectDifferentTargetForTest(source, otherCard));
    }

    @Test
    void stealPropertyStructuredFieldsDoNotChangeDefaultAdjustment() {
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType("ACTION");
        request.setCardId("sly_deal_1");
        request.setTargetPlayerId("p2");
        request.setTargetCardId("wild_1");
        request.setTargetZone("PROPERTY");
        AiHeuristics.AiPlayCandidate candidate = new AiHeuristics.AiPlayCandidate(
                "c1",
                request,
                "Action STEAL_PROPERTY target=p2 card=wild_1 takeColor=GREEN takeValue=4M "
                        + "completionGain=217 oppCompletionLoss=117 wild=true.");

        assertEquals(0d, SearchLookaheadAiPlayStrategy.candidateAdjustmentForTest(candidate));
    }

    @Test
    void wildOverfullPenaltyOnlyHitsAlreadyCompleteTargetColor() {
        AIPlayer bot = new AIPlayer("ai-1", "AI", new SearchLookaheadAiPlayStrategy());
        bot.addToPropertyZone(new PropertyCard("brown-1", "Brown 1", "BROWN"));
        bot.addToPropertyZone(new PropertyCard("brown-2", "Brown 2", "BROWN"));
        bot.addToPropertyZone(new PropertyCard("green-1", "Green 1", "GREEN"));
        PropertyWildCard anyWild = new PropertyWildCard("wild-any", "Any Wild");
        bot.receiveCardToHand(anyWild);

        PlayActionRequest overfullBrown = new PlayActionRequest();
        overfullBrown.setActionType("DEPLOY");
        overfullBrown.setCardId(anyWild.getId());
        overfullBrown.setTargetColorKey("BROWN");
        PlayActionRequest progressingGreen = new PlayActionRequest();
        progressingGreen.setActionType("DEPLOY");
        progressingGreen.setCardId(anyWild.getId());
        progressingGreen.setTargetColorKey("GREEN");

        assertEquals(
                -500d,
                SearchLookaheadAiPlayStrategy.wildDeployAdjustmentForTest(
                        bot, overfullBrown, 0, 0, 0, 500));
        assertEquals(
                0d,
                SearchLookaheadAiPlayStrategy.wildDeployAdjustmentForTest(
                        bot, progressingGreen, 0, 0, 0, 500));
    }

    @Test
    void boardAwareOverflowRetentionKeepsPassGoOverLowProgressProperty() {
        AIPlayer bot = new AIPlayer("ai-1", "AI", new SearchLookaheadAiPlayStrategy());
        bot.addToPropertyZone(new PropertyCard("brown-owned", "Brown", "BROWN"));
        PropertyCard lowProgress = new PropertyCard("railroad", "Railroad", "RAILROAD");
        ActionCard passGo = new ActionCard("pass-go", "Pass Go", "PASS_GO");

        assertTrue(SearchLookaheadAiPlayStrategy.overflowDiscardRetentionScoreForTest(bot, passGo)
                > SearchLookaheadAiPlayStrategy.overflowDiscardRetentionScoreForTest(bot, lowProgress));
    }

    @Test
    void boardAwareOverflowRetentionKeepsCompletingPropertyOverPassGo() {
        AIPlayer bot = new AIPlayer("ai-1", "AI", new SearchLookaheadAiPlayStrategy());
        bot.addToPropertyZone(new PropertyCard("brown-owned", "Brown", "BROWN"));
        PropertyCard completesBrown = new PropertyCard("brown-hand", "Brown", "BROWN");
        ActionCard passGo = new ActionCard("pass-go", "Pass Go", "PASS_GO");

        assertTrue(SearchLookaheadAiPlayStrategy.overflowDiscardRetentionScoreForTest(bot, completesBrown)
                > SearchLookaheadAiPlayStrategy.overflowDiscardRetentionScoreForTest(bot, passGo));
    }

    @Test
    void defaultOverflowChoiceStillUsesFallbackWhenExperimentDisabled() {
        SearchLookaheadAiPlayStrategy strategy = new SearchLookaheadAiPlayStrategy();
        AIPlayer bot = new AIPlayer("ai-1", "AI", strategy);
        Card fallbackDiscard = new MoneyCard("m1", "1M", 1);
        bot.receiveCardToHand(fallbackDiscard);
        bot.receiveCardToHand(new ActionCard("pass-go", "Pass Go", "PASS_GO"));

        List<Card> chosen = strategy.chooseOverflowDiscards(
                bot,
                null,
                1,
                List.of(fallbackDiscard));

        assertEquals(List.of(fallbackDiscard), chosen);
    }

    @Test
    void defaultPaymentChoiceStillUsesFallbackWhenExperimentDisabled() {
        SearchLookaheadAiPlayStrategy strategy = new SearchLookaheadAiPlayStrategy();
        AIPlayer bot = new AIPlayer("ai-1", "AI", strategy);
        MoneyCard fallbackCard = new MoneyCard("m1", "1M", 1);
        bot.addToBank(fallbackCard);
        PaymentSettlement.PaymentChoice fallback =
                new PaymentSettlement.PaymentChoice(List.of(fallbackCard), 1);

        PaymentSettlement.PaymentChoice chosen = strategy.choosePayment(bot, null, null, 1, fallback);

        assertEquals(fallback, chosen);
    }

    @Test
    void paymentTraceIncludesAuxiliaryMementoWhenPresent() {
        CapturingTraceSink sink = new CapturingTraceSink();
        SearchLookaheadAiPlayStrategy strategy =
                new SearchLookaheadAiPlayStrategy(sink, "trace-session");
        AIPlayer bot = new AIPlayer("ai-1", "AI", strategy);
        MoneyCard fallbackCard = new MoneyCard("m1", "1M", 1);
        bot.addToBank(fallbackCard);
        GameContext context = new GameContext();
        context.setAuxiliaryDecisionMementoJson("{\"sessionId\":\"s1\"}");
        PaymentSettlement.PaymentChoice fallback =
                new PaymentSettlement.PaymentChoice(List.of(fallbackCard), 1);

        strategy.choosePayment(bot, context, null, 1, fallback);

        SimulationDecisionRequest request = sink.onlyRequest();
        assertEquals("PAYMENT", request.getDecisionKind());
        assertTrue(request.getContextJson().has("self"));
        assertTrue(request.getContextJson().getAsJsonObject("decision").has("payableCards"));
        assertEquals(
                "{\"sessionId\":\"s1\"}",
                request.getContextJson()
                        .getAsJsonObject("counterfactual")
                        .get("mementoJson")
                        .getAsString());
    }

    @Test
    void responseTraceIncludesPlayableJustSayNoCandidateWhenChosenPasses() {
        CapturingTraceSink sink = new CapturingTraceSink();
        SearchLookaheadAiPlayStrategy strategy =
                new SearchLookaheadAiPlayStrategy(sink, "trace-session");
        AIPlayer tenant = new AIPlayer("tenant", "Tenant", strategy);
        tenant.receiveCardToHand(new ActionCard("no-1", "Just Say No", "RENT_WAIVER"));
        GameContext context = new GameContext();
        context.pushEffect(EffectStackEntry.pendingRent("landlord", "tenant", "BROWN", 2));
        context.setResponseState(new StackResponseState(StackResponseState.Role.TENANT, "tenant", 0L));

        strategy.chooseResponse(tenant, context, false);

        SimulationDecisionRequest request = sink.onlyRequest();
        assertEquals("JUST_SAY_NO", request.getDecisionKind());
        assertEquals("PASS", sink.onlyResult().getChoiceId());
        assertTrue(request.hasCandidate("PLAY_JSN"));
    }

    @Test
    void boardAwarePaymentUsesBankOnlyWhenBankCanCover() {
        AIPlayer bot = new AIPlayer("ai-1", "AI", new SearchLookaheadAiPlayStrategy());
        MoneyCard m3 = new MoneyCard("m3", "3M", 3);
        PropertyCard brown1 = new PropertyCard("brown-1", "Brown 1", "BROWN");
        PropertyCard brown2 = new PropertyCard("brown-2", "Brown 2", "BROWN");
        bot.addToBank(m3);
        bot.addToPropertyZone(brown1);
        bot.addToPropertyZone(brown2);

        PaymentSettlement.PaymentChoice chosen =
                SearchLookaheadAiPlayStrategy.boardAwarePaymentChoiceForTest(bot, 2);

        assertEquals(List.of("m3"), chosen.cards().stream().map(Card::getId).toList());
    }

    @Test
    void boardAwarePaymentCanOverpayToAvoidBreakingCompleteSet() {
        AIPlayer bot = new AIPlayer("ai-1", "AI", new SearchLookaheadAiPlayStrategy());
        MoneyCard m2 = new MoneyCard("m2", "2M", 2);
        PropertyCard green = new PropertyCard("green-1", "Green", "GREEN");
        PropertyCard brown1 = new PropertyCard("brown-1", "Brown 1", "BROWN");
        PropertyCard brown2 = new PropertyCard("brown-2", "Brown 2", "BROWN");
        bot.addToBank(m2);
        bot.addToPropertyZone(green);
        bot.addToPropertyZone(brown1);
        bot.addToPropertyZone(brown2);

        PaymentSettlement.PaymentChoice chosen =
                SearchLookaheadAiPlayStrategy.boardAwarePaymentChoiceForTest(bot, 4);

        assertEquals(List.of("m2", "green-1"), chosen.cards().stream().map(Card::getId).toList());
        assertTrue(SearchLookaheadAiPlayStrategy.paymentBoardDamageForTest(bot, List.of(brown1))
                > SearchLookaheadAiPlayStrategy.paymentBoardDamageForTest(bot, List.of(green)));
    }

    @Test
    void boardAwarePaymentDoesNotMissExactBankPaymentWhenManyCombinationsExist() {
        AIPlayer bot = new AIPlayer("ai-1", "AI", new SearchLookaheadAiPlayStrategy());
        bot.addToBank(new MoneyCard("m5", "5M", 5));
        for (int i = 0; i < 12; i++) {
            bot.addToBank(new MoneyCard("m1-" + i, "1M", 1));
        }
        bot.addToBank(new MoneyCard("m2", "2M", 2));

        PaymentSettlement.PaymentChoice chosen =
                SearchLookaheadAiPlayStrategy.boardAwarePaymentChoiceForTest(bot, 2);

        assertEquals(List.of("m2"), chosen.cards().stream().map(Card::getId).toList());
        assertEquals(2, chosen.amountPaid());
    }

    @Test
    void responseTenantThresholdCanHoldSmallRentWaiver() {
        AIPlayer tenant = new AIPlayer("tenant", "Tenant", new SearchLookaheadAiPlayStrategy());
        tenant.receiveCardToHand(new ActionCard("no-1", "Just Say No", "RENT_WAIVER"));
        GameContext context = new GameContext();
        context.pushEffect(EffectStackEntry.pendingRent("landlord", "tenant", "BROWN", 3));
        context.setResponseState(new StackResponseState(StackResponseState.Role.TENANT, "tenant", 0L));

        AiHeuristics.AiResponseDecision decision =
                SearchLookaheadAiPlayStrategy.responseDecisionForThresholdsForTest(
                        tenant, context, false, 4, 5);

        assertEquals(false, decision.playWaiver());
        assertTrue(decision.modelWorthAsking());
    }

    @Test
    void responseCounterThresholdCanCounterSmallWaiver() {
        AIPlayer landlord = new AIPlayer("landlord", "Landlord", new SearchLookaheadAiPlayStrategy());
        landlord.receiveCardToHand(new ActionCard("no-1", "Just Say No", "RENT_WAIVER"));
        EffectStackEntry rent = EffectStackEntry.pendingRent("landlord", "tenant", "BROWN", 3);
        GameContext context = new GameContext();
        context.pushEffect(rent);
        context.pushEffect(EffectStackEntry.waiver("tenant", rent.getId()));
        context.setResponseState(new StackResponseState(
                StackResponseState.Role.LANDLORD_COUNTER,
                "landlord",
                0L));

        AiHeuristics.AiResponseDecision decision =
                SearchLookaheadAiPlayStrategy.responseDecisionForThresholdsForTest(
                        landlord, context, true, 3, 3);

        assertEquals(true, decision.playWaiver());
        assertEquals("landlord", decision.request().getActingPlayerId());
    }

    private static GameStateSnapshot gameOverSnapshot() {
        GameStateSnapshot snapshot = new GameStateSnapshot();
        snapshot.setGameOver(true);
        return snapshot;
    }

    private static final class CapturingTraceSink implements DecisionTraceSink {
        private final List<SimulationDecisionRequest> requests = new ArrayList<>();
        private final List<SimulationDecisionResult> results = new ArrayList<>();

        @Override
        public void record(SimulationDecisionRequest request, SimulationDecisionResult result) {
            requests.add(request);
            results.add(result);
        }

        SimulationDecisionRequest onlyRequest() {
            assertEquals(1, requests.size());
            return requests.get(0);
        }

        SimulationDecisionResult onlyResult() {
            assertEquals(1, results.size());
            return results.get(0);
        }
    }
}
