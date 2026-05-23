package com.monopoly.pattern.strategy;

import com.monopoly.dto.PlayActionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekAiPlayStrategyCandidateScoreTest {

    @Test
    void invalidModelChoiceFallsBackToHighestScoredLocalCandidate() {
        AiHeuristics.AiPlayCandidate bankOne = candidate(
                "c1",
                "DEPOSIT",
                "m1",
                "Deposit money/bankable card for 1M.");
        AiHeuristics.AiPlayCandidate dealBreaker = candidate(
                "c2",
                "ACTION",
                "deal-breaker",
                "Action DEAL_BREAKER target=h1 completeSet=BLUE.");

        AiHeuristics.AiPlayCandidate chosen =
                DeepSeekAiPlayStrategy.bestLocalCandidate(List.of(bankOne, dealBreaker));

        assertEquals("c2", chosen.id());
    }

    @Test
    void bankedActionCardNamesDoNotReceiveStrategicActionScore() {
        AiHeuristics.AiPlayCandidate bankedDealBreaker = candidate(
                "c1",
                "DEPOSIT",
                "deal-breaker",
                "Deposit action card DEAL_BREAKER for 5M.");
        AiHeuristics.AiPlayCandidate playedDealBreaker = candidate(
                "c2",
                "ACTION",
                "deal-breaker",
                "Action DEAL_BREAKER target=h1 completeSet=RED.");

        assertTrue(DeepSeekAiPlayStrategy.candidateScore(playedDealBreaker)
                > DeepSeekAiPlayStrategy.candidateScore(bankedDealBreaker));
    }

    @Test
    void malformedDecisionCanStillYieldLegalCandidateIdWithoutRetry() {
        AiHeuristics.AiPlayCandidate first = candidate(
                "c1",
                "DEPOSIT",
                "m1",
                "Deposit money/bankable card for 1M.");
        AiHeuristics.AiPlayCandidate second = candidate(
                "c2",
                "ACTION",
                "pass-go",
                "Action PASS_GO.");

        String id = DeepSeekAiPlayStrategy.candidateIdFromMalformedDecision(
                "{\"candidateId\":\"c2\",\"reason\":\"unfinished",
                List.of(first, second));

        assertEquals("c2", id);
    }

    @Test
    void malformedDecisionIgnoresUnknownCandidateId() {
        AiHeuristics.AiPlayCandidate first = candidate(
                "c1",
                "DEPOSIT",
                "m1",
                "Deposit money/bankable card for 1M.");

        String id = DeepSeekAiPlayStrategy.candidateIdFromMalformedDecision(
                "{\"candidateId\":\"c999\",\"reason\":\"unfinished",
                List.of(first));

        assertEquals(null, id);
    }

    private static AiHeuristics.AiPlayCandidate candidate(
            String id,
            String actionType,
            String cardId,
            String summary) {
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType(actionType);
        request.setCardId(cardId);
        return new AiHeuristics.AiPlayCandidate(id, request, summary);
    }
}
