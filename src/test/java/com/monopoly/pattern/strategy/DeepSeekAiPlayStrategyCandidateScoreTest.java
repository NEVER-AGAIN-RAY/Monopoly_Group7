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

    @org.junit.jupiter.api.AfterEach
    void clearTeamAwareFlag() {
        System.clearProperty("monopoly.deepseek.teamAware");
    }

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

        assertEquals("deepseek-decision-context-v4", prompt.get("promptVersion").getAsString());
        assertEquals(2, prompt.getAsJsonObject("gameMeta").get("playerCount").getAsInt());
        assertEquals("PLAY_CARD", prompt.getAsJsonObject("decision").get("kind").getAsString());
        assertEquals(1, prompt.getAsJsonObject("self").getAsJsonArray("handCards").size());
        assertEquals(2, prompt.getAsJsonArray("players").size());
        assertEquals(3, prompt.getAsJsonObject("riskAssessment").get("selfSetsNeededToWin").getAsInt());
        JsonObject decision = prompt.getAsJsonObject("decision");
        assertTrue(decision.getAsJsonArray("candidateSelectionProtocol").size() > 0);
        JsonObject tactic = decision.getAsJsonArray("legalCandidates")
                .get(0).getAsJsonObject().getAsJsonObject("tactics");
        assertEquals("ACTION", tactic.get("actionType").getAsString());
        assertEquals("card-draw-tempo", tactic.getAsJsonArray("tags").get(0).getAsString());
        String serialized = prompt.toString();
        assertTrue(serialized.contains("\"handCount\":1"));
        assertTrue(!serialized.contains("hidden"));
    }

    @Test
    void teamAwarePromptMarksSameTeamLlmTargets() {
        System.setProperty("monopoly.deepseek.teamAware", "true");
        AIPlayer bot = new AIPlayer("ai-3", "DeepSeek-AI-3", null);
        bot.receiveCardToHand(new ActionCard("deal-breaker", "Deal Breaker", "DEAL_BREAKER"));
        AIPlayer teammate = new AIPlayer("ai-4", "DeepSeek-AI-4", null);
        teammate.addToPropertyZone(new PropertyCard("team-blue-1", "Team Blue 1", "DARK_BLUE"));
        teammate.addToPropertyZone(new PropertyCard("team-blue-2", "Team Blue 2", "DARK_BLUE"));
        HumanPlayer hard = new HumanPlayer("ai-1", "AI-Hard-1");
        GameContext context = new GameContext();
        context.bindPlayers(List.of(hard, bot, teammate));
        context.setTurnState(bot.getPlayerId(), "PLAY", 3, 1, 3);
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType("ACTION");
        request.setCardId("deal-breaker");
        request.setTargetPlayerId("ai-4");
        request.setTargetColorKey("DARK_BLUE");
        AiHeuristics.AiPlayCandidate candidate = new AiHeuristics.AiPlayCandidate(
                "c1", request, "Action DEAL_BREAKER target=ai-4 completeSet=DARK_BLUE.");

        JsonObject prompt = JsonParser.parseString(
                DeepSeekAiPlayStrategy.buildUserPrompt(bot, context, List.of(candidate))).getAsJsonObject();

        assertEquals("deepseek-decision-context-v4-team-aware", prompt.get("promptVersion").getAsString());
        assertTrue(prompt.getAsJsonObject("evaluationMode").get("teamAware").getAsBoolean());
        JsonObject tactic = prompt.getAsJsonObject("decision").getAsJsonArray("legalCandidates")
                .get(0).getAsJsonObject().getAsJsonObject("tactics");
        assertTrue(tactic.get("targetSameTeam").getAsBoolean());
        assertTrue(tactic.get("modelHint").getAsString().contains("Do not choose"));
    }

    @Test
    void teamAwarePruningRemovesSameTeamDestructiveTargetsOnly() {
        System.setProperty("monopoly.deepseek.teamAware", "true");
        AIPlayer bot = new AIPlayer("ai-3", "DeepSeek-AI-3", null);
        AIPlayer teammate = new AIPlayer("ai-4", "DeepSeek-AI-4", null);
        HumanPlayer hard = new HumanPlayer("ai-1", "AI-Hard-1");
        GameContext context = new GameContext();
        context.bindPlayers(List.of(hard, bot, teammate));
        context.setTurnState(bot.getPlayerId(), "PLAY", 3, 1, 3);

        AiHeuristics.AiPlayCandidate sameTeamSteal = targetedCandidate(
                "c1", "STEAL_PROPERTY", "steal-team", "ai-4",
                "Action STEAL_PROPERTY target=ai-4 card=team-blue.");
        AiHeuristics.AiPlayCandidate hardSteal = targetedCandidate(
                "c2", "STEAL_PROPERTY", "steal-hard", "ai-1",
                "Action STEAL_PROPERTY target=ai-1 card=hard-red.");
        AiHeuristics.AiPlayCandidate passGo = candidate(
                "c3", "ACTION", "pass-go", "Action PASS_GO.");

        List<AiHeuristics.AiPlayCandidate> pruned = DeepSeekAiPlayStrategy.pruneCandidates(
                bot, context, List.of(sameTeamSteal, hardSteal, passGo));

        assertEquals(2, pruned.size());
        assertEquals("ai-1", pruned.get(0).request().getTargetPlayerId());
        assertEquals("c1", pruned.get(0).id());
        assertEquals("pass-go", pruned.get(1).request().getCardId());
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

    private static AiHeuristics.AiPlayCandidate targetedCandidate(
            String id,
            String actionType,
            String cardId,
            String targetPlayerId,
            String summary) {
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType(actionType);
        request.setCardId(cardId);
        request.setTargetPlayerId(targetPlayerId);
        return new AiHeuristics.AiPlayCandidate(id, request, summary);
    }

    private static GameContext contextWithPlayers(AIPlayer bot, HumanPlayer other) {
        GameContext context = new GameContext();
        context.bindPlayers(List.of(bot, other));
        context.setTurnState(bot.getPlayerId(), "PLAY", 3, 1, 3);
        return context;
    }
}
