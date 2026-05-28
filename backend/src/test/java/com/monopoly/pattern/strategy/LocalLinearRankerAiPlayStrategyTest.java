package com.monopoly.pattern.strategy;

import com.monopoly.dto.PlayActionRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

        assertEquals(169, features.length);
    }

    @Test
    void featureExtractorKeepsSourcePolicySignalsAtTheTail() {
        JsonObject context = JsonParser.parseString("""
                {
                  "sourcePolicy": {
                    "choiceId": "c2",
                    "metadata": {
                      "source": "lookahead",
                      "hardChoiceId": "c1",
                      "modelBestId": "c2",
                      "modelBestScore": 3000,
                      "hardChoiceScore": 1000,
                      "candidateScores": {"c1": 1000, "c2": 3000, "c3": 2500},
                      "candidateAdjustments": {"c3": 500},
                      "hardMargin": 0,
                      "hardFallbackUsed": false,
                      "rolloutRemainingTurn": false
                    }
                  }
                }
                """).getAsJsonObject();

        double[] features = LocalRankerAiPlayStrategy.FeatureExtractor.featuresFor(
                "PLAY_CARD",
                1L,
                context,
                new JsonObject(),
                "c3",
                "Action RENT expectedPaid=5.");

        assertEquals(169, features.length);
        assertEquals(1.0, features[123], 0.000001);
        assertEquals(0.0, features[124], 0.000001);
        assertEquals(0.0, features[125], 0.000001);
        assertEquals(0.0, features[126], 0.000001);
        assertEquals(2500 / 15000.0, features[127], 0.000001);
        assertEquals(-500 / 15000.0, features[131], 0.000001);
        assertEquals(1500 / 15000.0, features[132], 0.000001);
        assertEquals(-500 / 15000.0, features[133], 0.000001);
        assertEquals(500 / 5000.0, features[134], 0.000001);
    }

    @Test
    void featureExtractorAddsStructuredStealSummarySignals() {
        double[] features = LocalRankerAiPlayStrategy.FeatureExtractor.featuresFor(
                "PLAY_CARD",
                1L,
                new JsonObject(),
                new JsonObject(),
                "c3",
                "Action STEAL_PROPERTY target=p2 card=wild_1 takeColor=GREEN "
                        + "takeValue=4M completionGain=217 oppCompletionLoss=117 wild=true.");

        assertEquals(169, features.length);
        assertEquals(4.0 / 100.0, features[101], 0.000001);
        assertEquals(0.0, features[102], 0.000001);
        assertEquals(1.0, features[105], 0.000001);
        assertEquals(0.0, features[106], 0.000001);
    }

    @Test
    void featureExtractorAddsRuntimeVisibleTacticsSignals() {
        JsonObject context = JsonParser.parseString("""
                {
                  "decision": {
                    "legalCandidates": [
                      {
                        "id": "c3",
                        "summary": "Action RENT expectedPaid=5.",
                        "tactics": {
                          "localScore": 9000,
                          "netScore": 7000,
                          "materialGain": 6000,
                          "completionGain": 50,
                          "opponentCompletionLoss": 25,
                          "expectedPaidM": 5,
                          "selfCompleteSets": 2,
                          "targetCompleteSets": 1,
                          "selfNearWin": true,
                          "targetIsLeader": true,
                          "targetNearWin": false,
                          "targetSameTeam": false,
                          "targetHardOpponent": true,
                          "targetHardOrLocalOpponent": true,
                          "isPassiveCash": false,
                          "tags": ["cash-pressure", "attacks-current-leader"]
                        }
                      }
                    ]
                  }
                }
                """).getAsJsonObject();

        double[] features = LocalRankerAiPlayStrategy.FeatureExtractor.featuresFor(
                "PLAY_CARD",
                1L,
                context,
                new JsonObject(),
                "c3",
                "Action RENT expectedPaid=5.");

        assertEquals(169, features.length);
        assertEquals(1.0, features[138], 0.000001);
        assertEquals(9000 / 15000.0, features[139], 0.000001);
        assertEquals(7000 / 15000.0, features[140], 0.000001);
        assertEquals(6000 / 15000.0, features[141], 0.000001);
        assertEquals(0.5, features[142], 0.000001);
        assertEquals(0.25, features[143], 0.000001);
        assertEquals(0.25, features[144], 0.000001);
        assertEquals(2.0 / 3.0, features[145], 0.000001);
        assertEquals(1.0 / 3.0, features[146], 0.000001);
        assertEquals(1.0, features[147], 0.000001);
        assertEquals(1.0, features[148], 0.000001);
        assertEquals(0.0, features[149], 0.000001);
        assertEquals(0.0, features[150], 0.000001);
        assertEquals(1.0, features[151], 0.000001);
        assertEquals(1.0, features[152], 0.000001);
        assertEquals(0.0, features[153], 0.000001);
        assertEquals(1.0, features[159], 0.000001);
        assertEquals(1.0, features[162], 0.000001);
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
    void localRankerDefaultsToHardForPaymentUnlessKindEnabled() throws Exception {
        Path model = Files.createTempFile("ranker-payment", ".json");
        Files.writeString(model, flatMlpModelJson());
        AIPlayer debtor = new AIPlayer("ai-1", "AI", new HardAiPlayStrategy());
        AIPlayer creditor = new AIPlayer("ai-2", "Creditor", new HardAiPlayStrategy());
        MoneyCard two = new MoneyCard("m2", "2M", 2);
        MoneyCard three = new MoneyCard("m3", "3M", 3);
        debtor.addToBank(two);
        debtor.addToBank(three);
        GameContext context = new GameContext();
        context.bindPlayers(java.util.List.of(debtor, creditor));
        PaymentSettlement.PaymentChoice fallback = PaymentSettlement.choosePayment(debtor, 2);
        LocalRankerAiPlayStrategy strategy = new LocalRankerAiPlayStrategy(model);

        PaymentSettlement.PaymentChoice chosen =
                strategy.choosePayment(debtor, context, creditor, 2, fallback);

        assertEquals(fallback.cards().stream().map(Card::getId).toList(),
                chosen.cards().stream().map(Card::getId).toList());
    }

    @Test
    void localRankerCanRankOnlyPaymentWhenKindEnabled() throws Exception {
        String previous = System.getProperty("monopoly.localRanker.rankedDecisionKinds");
        System.setProperty("monopoly.localRanker.rankedDecisionKinds", "PAYMENT");
        try {
            Path model = Files.createTempFile("ranker-payment", ".json");
            Files.writeString(model, flatMlpModelJson());
            AIPlayer debtor = new AIPlayer("ai-1", "AI", new HardAiPlayStrategy());
            AIPlayer creditor = new AIPlayer("ai-2", "Creditor", new HardAiPlayStrategy());
            MoneyCard two = new MoneyCard("m2", "2M", 2);
            MoneyCard three = new MoneyCard("m3", "3M", 3);
            debtor.addToBank(two);
            debtor.addToBank(three);
            debtor.receiveCardToHand(new MoneyCard("h1", "1M", 1));
            GameContext context = new GameContext();
            context.bindPlayers(java.util.List.of(debtor, creditor));
            PaymentSettlement.PaymentChoice fallback = PaymentSettlement.choosePayment(debtor, 2);
            LocalRankerAiPlayStrategy strategy = new LocalRankerAiPlayStrategy(model);

            PaymentSettlement.PaymentChoice chosen =
                    strategy.choosePayment(debtor, context, creditor, 2, fallback);
            AtomicReference<PlayActionRequest> submitted = new AtomicReference<>();
            boolean played = strategy.tryPlayOneCard(debtor, context, submitted::set);

            assertEquals(true, LocalRankerAiPlayStrategy.shouldRankDecisionKindForTest("PAYMENT"));
            assertFalse(LocalRankerAiPlayStrategy.shouldRankDecisionKindForTest("PLAY_CARD"));
            assertNotNull(chosen);
            assertFalse(chosen.cards().isEmpty());
            assertEquals(true, played);
            assertNotNull(submitted.get());
            assertEquals("DEPOSIT", submitted.get().getActionType());
        } finally {
            restore("monopoly.localRanker.rankedDecisionKinds", previous);
        }
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
    void localRankerDefaultsToHardForJustSayNoWhenAuxiliaryRankingDisabled() throws Exception {
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

        assertEquals(AiHeuristics.chooseResponse(target, context, false).playWaiver(), decision.playWaiver());
    }

    @Test
    void actionSpecificHybridMarginsOverrideTheGlobalMargin() {
        System.setProperty("monopoly.localRanker.hybridMargin", "0.5");
        System.setProperty("monopoly.localRanker.hybridMargin.deploy", "1.2");
        System.setProperty("monopoly.localRanker.hybridMargin.passGo", "1.5");
        System.setProperty("monopoly.localRanker.hybridMargin.swingAction", "0.1");
        try {
            PlayActionRequest deploy = new PlayActionRequest();
            deploy.setActionType("DEPLOY");
            PlayActionRequest passGo = new PlayActionRequest();
            passGo.setActionType("ACTION");
            PlayActionRequest steal = new PlayActionRequest();
            steal.setActionType("ACTION");

            assertEquals(1.2, LocalRankerAiPlayStrategy.hybridMarginForTest(
                    deploy, "Deploy property YELLOW completionScore=33."), 0.000001);
            assertEquals(1.5, LocalRankerAiPlayStrategy.hybridMarginForTest(
                    passGo, "Action PASS_GO."), 0.000001);
            assertEquals(0.1, LocalRankerAiPlayStrategy.hybridMarginForTest(
                    steal, "Action STEAL_PROPERTY target=ai-1 card=PROP_1."), 0.000001);
        } finally {
            System.clearProperty("monopoly.localRanker.hybridMargin");
            System.clearProperty("monopoly.localRanker.hybridMargin.deploy");
            System.clearProperty("monopoly.localRanker.hybridMargin.passGo");
            System.clearProperty("monopoly.localRanker.hybridMargin.swingAction");
        }
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

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
