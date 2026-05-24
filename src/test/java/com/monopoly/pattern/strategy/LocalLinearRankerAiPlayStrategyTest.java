package com.monopoly.pattern.strategy;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.settlement.PaymentSettlement;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LocalLinearRankerAiPlayStrategyTest {

    @Test
    void featureExtractorProducesExpectedDimension() {
        double[] features = LocalRankerAiPlayStrategy.FeatureExtractor.featuresFor(
                "PLAY_CARD",
                new GameContext(),
                new com.google.gson.JsonObject(),
                new com.google.gson.JsonObject(),
                "c1",
                "Deposit money/bankable card for 1M.");

        assertEquals(119, features.length);
    }

    @Test
    void localLinearRankerCanChooseLegalCandidate() throws Exception {
        Path model = Files.createTempFile("ranker", ".json");
        Files.writeString(model, flatModelJson());
        AIPlayer bot = new AIPlayer("ai-1", "AI", new HardAiPlayStrategy());
        bot.receiveCardToHand(new MoneyCard("m1", "1M", 1));
        bot.receiveCardToHand(new MoneyCard("m2", "2M", 2));
        GameContext context = new GameContext();
        context.bindPlayers(java.util.List.of(bot));
        context.setTurnState("ai-1", "PLAY", 1, 0, 3);
        LocalLinearRankerAiPlayStrategy strategy = new LocalLinearRankerAiPlayStrategy(model);
        AtomicReference<PlayActionRequest> submitted = new AtomicReference<>();
        AiGameBridge bridge = submitted::set;

        boolean played = strategy.tryPlayOneCard(bot, context, bridge);

        assertEquals(true, played);
        assertNotNull(submitted.get());
        assertEquals("DEPOSIT", submitted.get().getActionType());
    }

    @Test
    void localMlpRankerCanChooseLegalCandidate() throws Exception {
        Path model = Files.createTempFile("ranker-mlp", ".json");
        Files.writeString(model, flatMlpModelJson());
        AIPlayer bot = new AIPlayer("ai-1", "AI", new HardAiPlayStrategy());
        bot.receiveCardToHand(new MoneyCard("m1", "1M", 1));
        bot.receiveCardToHand(new MoneyCard("m2", "2M", 2));
        GameContext context = new GameContext();
        context.bindPlayers(java.util.List.of(bot));
        context.setTurnState("ai-1", "PLAY", 1, 0, 3);
        LocalRankerAiPlayStrategy strategy = new LocalRankerAiPlayStrategy(model);
        AtomicReference<PlayActionRequest> submitted = new AtomicReference<>();
        AiGameBridge bridge = submitted::set;

        boolean played = strategy.tryPlayOneCard(bot, context, bridge);

        assertEquals(true, played);
        assertNotNull(submitted.get());
        assertEquals("DEPOSIT", submitted.get().getActionType());
    }

    @Test
    void localRankerCanChoosePaymentCandidate() throws Exception {
        Path model = Files.createTempFile("ranker-payment", ".json");
        Files.writeString(model, flatMlpModelJson());
        AIPlayer debtor = new AIPlayer("ai-1", "AI", new HardAiPlayStrategy());
        AIPlayer creditor = new AIPlayer("ai-2", "Creditor", new HardAiPlayStrategy());
        MoneyCard one = new MoneyCard("m1", "1M", 1);
        MoneyCard two = new MoneyCard("m2", "2M", 2);
        debtor.addToBank(one);
        debtor.addToBank(two);
        GameContext context = new GameContext();
        context.bindPlayers(java.util.List.of(debtor, creditor));
        PaymentSettlement.PaymentChoice fallback = PaymentSettlement.choosePayment(debtor, 2);
        LocalRankerAiPlayStrategy strategy = new LocalRankerAiPlayStrategy(model);

        PaymentSettlement.PaymentChoice chosen =
                strategy.choosePayment(debtor, context, creditor, 2, fallback);

        assertNotNull(chosen);
        assertFalse(chosen.cards().isEmpty());
        assertEquals(true, chosen.amountPaid() >= 2);
    }

    @Test
    void localRankerCanChooseOverflowDiscards() throws Exception {
        Path model = Files.createTempFile("ranker-discard", ".json");
        Files.writeString(model, flatMlpModelJson());
        AIPlayer bot = new AIPlayer("ai-1", "AI", new HardAiPlayStrategy());
        for (int i = 1; i <= 8; i++) {
            bot.receiveCardToHand(new MoneyCard("m" + i, i + "M", i));
        }
        GameContext context = new GameContext();
        context.bindPlayers(java.util.List.of(bot));
        LocalRankerAiPlayStrategy strategy = new LocalRankerAiPlayStrategy(model);
        List<Card> fallback = bot.chooseOverflowDiscardsTo(7);

        List<Card> chosen = strategy.chooseOverflowDiscards(bot, context, 7, fallback);

        assertEquals(1, chosen.size());
        assertEquals(8, bot.getHandCardCount());
    }

    @Test
    void localRankerCanChooseJustSayNoPassCandidate() throws Exception {
        Path model = Files.createTempFile("ranker-jsn", ".json");
        Files.writeString(model, flatMlpModelJson());
        AIPlayer actor = new AIPlayer("ai-actor", "Actor", new HardAiPlayStrategy());
        AIPlayer target = new AIPlayer("ai-target", "Target", new HardAiPlayStrategy());
        ActionCard justSayNo = new ActionCard("no-1", "Just Say No", "RENT_WAIVER");
        target.receiveCardToHand(justSayNo);
        GameContext context = new GameContext();
        context.bindPlayers(java.util.List.of(actor, target));
        context.pushEffect(EffectStackEntry.pendingAction(actor.getPlayerId(), target.getPlayerId()));
        context.setResponseState(
                new StackResponseState(StackResponseState.Role.TENANT, target.getPlayerId(), 0L));
        LocalRankerAiPlayStrategy strategy = new LocalRankerAiPlayStrategy(model);

        AiHeuristics.AiResponseDecision decision =
                strategy.chooseResponse(target, context, false);

        assertFalse(decision.playWaiver());
    }

    private static String flatModelJson() {
        StringBuilder weights = new StringBuilder();
        for (int i = 0; i < 119; i++) {
            if (i > 0) {
                weights.append(',');
            }
            weights.append('0');
        }
        return "{"
                + "\"schema\":\"monopoly-deal-linear-ranker-v1\","
                + "\"featureVersion\":\"candidate-ranker-features-v1\","
                + "\"inputDim\":119,"
                + "\"bias\":0,"
                + "\"weights\":[" + weights + "]"
                + "}";
    }

    private static String flatMlpModelJson() {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < 119; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append('0');
        }
        return "{"
                + "\"schema\":\"monopoly-deal-mlp-ranker-v1\","
                + "\"featureVersion\":\"candidate-ranker-features-v1\","
                + "\"inputDim\":119,"
                + "\"layers\":["
                + "{\"type\":\"dense\",\"activation\":\"linear\",\"inputDim\":119,\"outputDim\":1,"
                + "\"weights\":[[" + row + "]],\"bias\":[0]}"
                + "]"
                + "}";
    }
}
