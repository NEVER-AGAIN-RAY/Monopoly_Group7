package com.monopoly.pattern.strategy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.settlement.PaymentSettlement;
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

    @Test
    void malformedCardIdsCanStillYieldLegalIds() {
        List<String> ids = DeepSeekAiPlayStrategy.cardIdsFromMalformedDecision(
                "{\"cardIds\":[\"m1\",\"p2\"],\"reason\":\"unfinished",
                List.of("m1", "p2", "p3"));

        assertEquals(List.of("m1", "p2"), ids);
    }

    @Test
    void malformedCardIdsRejectUnknownIds() {
        List<String> ids = DeepSeekAiPlayStrategy.cardIdsFromMalformedDecision(
                "{\"cardIds\":[\"m1\",\"bad\"],\"reason\":\"unfinished",
                List.of("m1", "p2"));

        assertEquals(List.of(), ids);
    }

    @Test
    void playPromptUsesSharedContextWithoutOpponentHandDetails() {
        AIPlayer bot = new AIPlayer("ai-1", "DeepSeek-AI-1", null);
        bot.receiveCardToHand(new ActionCard("pass-go", "Pass Go", "PASS_GO"));
        bot.addToPropertyZone(new PropertyCard("ai-brown", "AI Brown", "BROWN"));
        HumanPlayer human = new HumanPlayer("human", "Human");
        human.receiveCardToHand(new MoneyCard("hidden", "Hidden 5M", 5));
        human.addToPropertyZone(new PropertyCard("human-red", "Human Red", "RED"));
        GameContext context = contextWithPlayers(bot, human);
        AiHeuristics.AiPlayCandidate candidate = candidate(
                "c1", "ACTION", "pass-go", "Action PASS_GO draw=2.");

        JsonObject prompt = JsonParser.parseString(
                DeepSeekAiPlayStrategy.buildUserPrompt(bot, context, List.of(candidate))).getAsJsonObject();

        assertEquals("deepseek-decision-context-v3", prompt.get("promptVersion").getAsString());
        assertEquals(2, prompt.getAsJsonObject("gameMeta").get("playerCount").getAsInt());
        assertEquals("PLAY_CARD", prompt.getAsJsonObject("decision").get("kind").getAsString());
        assertEquals(1, prompt.getAsJsonObject("self").getAsJsonArray("handCards").size());
        assertEquals(2, prompt.getAsJsonArray("players").size());
        assertEquals(3, prompt.getAsJsonObject("riskAssessment").get("selfSetsNeededToWin").getAsInt());
        String serialized = prompt.toString();
        assertTrue(serialized.contains("\"handCount\":1"));
        assertTrue(!serialized.contains("hidden"));
    }

    @Test
    void paymentAndDiscardPromptsReuseSharedContext() {
        AIPlayer bot = new AIPlayer("ai-1", "DeepSeek-AI-1", null);
        MoneyCard cash = new MoneyCard("cash-1", "1M", 1);
        MoneyCard handOverflow = new MoneyCard("hand-overflow", "2M", 2);
        bot.receiveCardToHand(handOverflow);
        bot.addToBank(cash);
        HumanPlayer creditor = new HumanPlayer("human", "Human");
        GameContext context = contextWithPlayers(bot, creditor);
        PaymentSettlement.PaymentChoice fallback =
                new PaymentSettlement.PaymentChoice(List.of(cash), cash.getPaymentValue());

        JsonObject payment = JsonParser.parseString(
                DeepSeekAiPlayStrategy.buildPaymentPrompt(
                        bot, context, creditor, 1, List.of(cash), fallback)).getAsJsonObject();
        JsonObject discard = JsonParser.parseString(
                DeepSeekAiPlayStrategy.buildDiscardPrompt(
                        bot, context, 7, 1, List.of(handOverflow))).getAsJsonObject();

        assertEquals("PAYMENT", payment.getAsJsonObject("decision").get("kind").getAsString());
        assertEquals("human", payment.getAsJsonObject("decision").get("creditorPlayerId").getAsString());
        assertEquals(2, payment.getAsJsonObject("gameMeta").get("playerCount").getAsInt());
        assertEquals("OVERFLOW_DISCARD", discard.getAsJsonObject("decision").get("kind").getAsString());
        assertEquals(2, discard.getAsJsonObject("gameMeta").get("playerCount").getAsInt());
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

    private static GameContext contextWithPlayers(AIPlayer bot, HumanPlayer other) {
        GameContext context = new GameContext();
        context.bindPlayers(List.of(bot, other));
        context.setTurnState(bot.getPlayerId(), "PLAY", 3, 1, 3);
        return context;
    }
}
