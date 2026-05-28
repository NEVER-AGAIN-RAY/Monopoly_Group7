package com.monopoly.pattern.strategy;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.dto.PlayActionRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定 {@code monopoly.ai.seed} 下，三档策略在相同局面中应对产生不同目标（或不同阶段优先出牌类型）。
 */
class AiStrategyProfileDifferentiationTest {

    private String previousSeed;

    @BeforeEach
    void saveSeed() {
        previousSeed = System.getProperty("monopoly.ai.seed");
        System.setProperty("monopoly.ai.seed", "4242424242424242");
    }

    @AfterEach
    void restoreSeed() {
        if (previousSeed == null) {
            System.clearProperty("monopoly.ai.seed");
        } else {
            System.setProperty("monopoly.ai.seed", previousSeed);
        }
    }

    @Test
    void hardTargetsHighestThreatLegalStealWhenThreatAndValueDiverge() {
        PlayActionRequest hard = firstStealRequest(AiStrategyProfile.HARD);
        assertNotNull(hard);
        assertEquals("h1", hard.getTargetPlayerId());
    }

    @Test
    void normalPrioritizesDeployOverStealWhenBothInHand() {
        RecordingBridge bridge = new RecordingBridge();
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("steal1", "s", "STEAL_PROPERTY"));
        bot.receiveCardToHand(new PropertyCard("brownA", "b", "BROWN"));

        HumanPlayer rich = new HumanPlayer("h1", "H1");
        rich.addToPropertyZone(new PropertyCard("b1", "b1", "BROWN"));
        rich.addToPropertyZone(new PropertyCard("b2", "b2", "BROWN"));

        HumanPlayer poor = new HumanPlayer("h2", "H2");
        poor.addToPropertyZone(new PropertyCard("r1", "r1", "RAILROAD"));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, rich, poor));

        AiHeuristics.tryPlayOneCard(AiStrategyProfile.NORMAL, bot, ctx, bridge);
        assertTrue(bridge.last != null && "DEPLOY".equals(bridge.last.getActionType()));

        AIPlayer bot2 = new AIPlayer("ai2", "AI", null);
        bot2.receiveCardToHand(new ActionCard("steal1", "s", "STEAL_PROPERTY"));
        bot2.receiveCardToHand(new PropertyCard("brownA", "b", "BROWN"));
        GameContext ctx2 = new GameContext();
        ctx2.bindPlayers(List.of(bot2, rich, poor));
        RecordingBridge bridgeEasy = new RecordingBridge();
        AiHeuristics.tryPlayOneCard(AiStrategyProfile.EASY, bot2, ctx2, bridgeEasy);
        assertNotNull(bridgeEasy.last);
    }

    @Test
    void candidatesDoNotBankProtectedActionCardsWhenHandIsNotOverflowing() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("deal-breaker", "Deal Breaker", "DEAL_BREAKER"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().noneMatch(c ->
                "DEPOSIT".equals(c.request().getActionType())
                        && "deal-breaker".equals(c.request().getCardId())));
    }

    @Test
    void candidatesStillBankProtectedActionCardAsLastOverflowResort() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("deal-breaker", "Deal Breaker", "DEAL_BREAKER"));
        for (int i = 0; i < 7; i++) {
            bot.receiveCardToHand(new PropertyCard("p" + i, "p" + i, "RED"));
        }

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().anyMatch(c ->
                "DEPOSIT".equals(c.request().getActionType())
                        && "deal-breaker".equals(c.request().getCardId())));
    }

    @Test
    void candidatesPreferBankingMoneyBeforeProtectedActionCardDuringOverflow() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("deal-breaker", "Deal Breaker", "DEAL_BREAKER"));
        bot.receiveCardToHand(new MoneyCard("m1", "1M", 1));
        for (int i = 0; i < 6; i++) {
            bot.receiveCardToHand(new PropertyCard("p" + i, "p" + i, "RED"));
        }

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().anyMatch(c ->
                "DEPOSIT".equals(c.request().getActionType())
                        && "m1".equals(c.request().getCardId())));
        assertTrue(candidates.stream().noneMatch(c ->
                "DEPOSIT".equals(c.request().getActionType())
                        && "deal-breaker".equals(c.request().getCardId())));
    }

    @Test
    void doubleRentCandidateRequiresImmediateRentFollowUp() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("double", "Double Rent", "DOUBLE_RENT"));
        bot.addToPropertyZone(new PropertyCard("brown", "Brown", "BROWN"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        opponent.addToBank(new MoneyCard("m1", "1M", 1));
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));
        ctx.setTurnActionBudget(0, 3);

        List<AiHeuristics.AiPlayCandidate> withoutRent =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);
        assertTrue(withoutRent.stream().noneMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "double".equals(c.request().getCardId())));

        bot.receiveCardToHand(new ActionCard("rent", "Rent", "RENT"));
        List<AiHeuristics.AiPlayCandidate> withRent =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);
        assertTrue(withRent.stream().anyMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "double".equals(c.request().getCardId())));
    }

    @Test
    void doubleRentCandidateRequiresEnoughRemainingActions() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("double", "Double Rent", "DOUBLE_RENT"));
        bot.receiveCardToHand(new ActionCard("rent", "Rent", "RENT"));
        bot.addToPropertyZone(new PropertyCard("brown", "Brown", "BROWN"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        opponent.addToBank(new MoneyCard("m1", "1M", 1));
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));
        ctx.setTurnActionBudget(2, 3);

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().noneMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "double".equals(c.request().getCardId())));
    }

    @Test
    void rentCandidateRequiresExpectedPayment() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("rent", "Rent", "RENT"));
        bot.addToPropertyZone(new PropertyCard("brown", "Brown", "BROWN"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().noneMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "rent".equals(c.request().getCardId())));
    }

    @Test
    void birthdayCandidateRequiresCollectibleAssets() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("birthday", "Birthday", "BIRTHDAY"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().noneMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "birthday".equals(c.request().getCardId())));
    }

    @Test
    void birthdayCandidateDescribesActualCollectibleAmount() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("birthday", "Birthday", "BIRTHDAY"));

        HumanPlayer rich = new HumanPlayer("h1", "H1");
        rich.addToBank(new MoneyCard("m2", "2M", 2));
        HumanPlayer partial = new HumanPlayer("h2", "H2");
        partial.addToBank(new MoneyCard("m1", "1M", 1));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, rich, partial));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().anyMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "birthday".equals(c.request().getCardId())
                        && c.summary().contains("expectedPaid=3M")));
    }

    @Test
    void debtCollectorCandidateDescribesExpectedOverpayment() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("debt", "Debt Collector", "DEBT_COLLECTOR"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        opponent.addToBank(new MoneyCard("m10", "10M", 10));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().anyMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "debt".equals(c.request().getCardId())
                        && c.summary().contains("due=5M expectedPaid=10M")));
    }

    @Test
    void forcedDealCandidatesPreferCompletionGainOverNeutralSameColorTrade() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("forced", "Forced Deal", "FORCED_DEAL"));
        bot.addToPropertyZone(new PropertyCard("my-brown", "My Brown", "BROWN"));
        bot.addToPropertyZone(new PropertyCard("my-red", "My Red", "RED"));
        bot.addToPropertyZone(new PropertyCard("my-rail", "My Rail", "RAILROAD"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        opponent.addToPropertyZone(new PropertyCard("their-brown", "Their Brown", "BROWN"));
        opponent.addToPropertyZone(new PropertyCard("their-red", "Their Red", "RED"));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        AiHeuristics.AiPlayCandidate firstForcedDeal = candidates.stream()
                .filter(c -> "ACTION".equals(c.request().getActionType())
                        && "forced".equals(c.request().getCardId()))
                .findFirst()
                .orElseThrow();

        assertEquals("their-brown", firstForcedDeal.request().getTargetCardId());
        assertEquals("my-rail", firstForcedDeal.request().getActorCardId());
    }

    @Test
    void forcedDealCandidateSummaryIncludesTradeQualitySignals() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("forced", "Forced Deal", "FORCED_DEAL"));
        bot.addToPropertyZone(new PropertyCard("my-brown", "My Brown", "BROWN"));
        bot.addToPropertyZone(new PropertyCard("my-rail", "My Rail", "RAILROAD"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        opponent.addToPropertyZone(new PropertyCard("their-brown", "Their Brown", "BROWN"));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        String summary = candidates.stream()
                .filter(c -> "ACTION".equals(c.request().getActionType())
                        && "forced".equals(c.request().getCardId()))
                .findFirst()
                .orElseThrow()
                .summary();

        assertTrue(summary.contains("take=their-brown:BROWN"));
        assertTrue(summary.contains("give=my-rail:RAILROAD"));
        assertTrue(summary.contains("takeValue="));
        assertTrue(summary.contains("materialGain="));
        assertTrue(summary.contains("completionGain="));
        assertTrue(summary.contains("netScore="));
        assertTrue(summary.indexOf("netScore=") < summary.indexOf("target="));
    }

    @Test
    void forcedDealCandidatesExcludeNegativeValueTrades() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("forced", "Forced Deal", "FORCED_DEAL"));
        bot.addToPropertyZone(new PropertyCard("my-dark-blue-a", "My Dark Blue A", "DARK_BLUE"));
        bot.addToPropertyZone(new PropertyCard("my-dark-blue-b", "My Dark Blue B", "DARK_BLUE"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        opponent.addToPropertyZone(new PropertyCard("their-utility", "Their Utility", "UTILITY"));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().noneMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "forced".equals(c.request().getCardId())));
    }

    @Test
    void forcedDealCandidatesExcludeThreatOnlyNeutralTrades() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("forced", "Forced Deal", "FORCED_DEAL"));
        bot.addToPropertyZone(new PropertyCard("my-pink", "My Pink", "PINK"));

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        opponent.addToPropertyZone(new PropertyCard("their-orange", "Their Orange", "ORANGE"));
        opponent.addToBank(new MoneyCard("banked", "5M", 5));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().noneMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "forced".equals(c.request().getCardId())));
    }

    @Test
    void forcedDealCandidatesExcludeValueOnlyTrades() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("forced", "Forced Deal", "FORCED_DEAL"));
        PropertyWildCard wild = new PropertyWildCard(
                "my-wild",
                "My Wild",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("YELLOW", "RED"));
        wild.setAssignedColorKey("YELLOW");
        bot.addToPropertyZone(wild);

        HumanPlayer opponent = new HumanPlayer("h1", "H1");
        opponent.addToPropertyZone(new PropertyCard("their-light-blue", "Their Light Blue", "LIGHT_BLUE"));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, opponent));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        assertTrue(candidates.stream().noneMatch(c ->
                "ACTION".equals(c.request().getActionType())
                        && "forced".equals(c.request().getCardId())));
    }

    @Test
    void deployedWildCandidatesKeepLockedColorWhenReturnedToHand() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        PropertyWildCard wild = new PropertyWildCard(
                "locked-wild",
                "Locked Wild",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("YELLOW", "RED"));
        wild.setAssignedColorKey("YELLOW");
        bot.receiveCardToHand(wild);

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        List<String> deployColors = candidates.stream()
                .filter(c -> "DEPLOY".equals(c.request().getActionType()))
                .map(c -> c.request().getTargetColorKey())
                .toList();
        assertEquals(List.of("YELLOW"), deployColors);
    }

    @Test
    void wildDeployCandidateSummariesUseCompactDefaultText() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.addToPropertyZone(new PropertyCard("brown-owned", "Brown", "BROWN"));
        PropertyWildCard wild = new PropertyWildCard(
                "wild",
                "Wild",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("BROWN", "LIGHT_BLUE"));
        bot.receiveCardToHand(wild);

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        String brownSummary = candidates.stream()
                .filter(c -> "BROWN".equals(c.request().getTargetColorKey()))
                .findFirst()
                .orElseThrow()
                .summary();
        assertEquals("Deploy wild property as BROWN.", brownSummary);
    }

    @Test
    void wildDeployCandidateSummariesCanExposeTargetColorProgress() {
        String previous = System.getProperty("monopoly.ai.includeWildProgressInSummary");
        try {
            System.setProperty("monopoly.ai.includeWildProgressInSummary", "true");
            AIPlayer bot = new AIPlayer("ai", "AI", null);
            bot.addToPropertyZone(new PropertyCard("brown-owned", "Brown", "BROWN"));
            PropertyWildCard wild = new PropertyWildCard(
                    "wild",
                    "Wild",
                    PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                    List.of("BROWN", "LIGHT_BLUE"));
            bot.receiveCardToHand(wild);

            GameContext ctx = new GameContext();
            ctx.bindPlayers(List.of(bot));

            List<AiHeuristics.AiPlayCandidate> candidates =
                    AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

            String brownSummary = candidates.stream()
                    .filter(c -> "BROWN".equals(c.request().getTargetColorKey()))
                    .findFirst()
                    .orElseThrow()
                    .summary();
            String lightBlueSummary = candidates.stream()
                    .filter(c -> "LIGHT_BLUE".equals(c.request().getTargetColorKey()))
                    .findFirst()
                    .orElseThrow()
                    .summary();
            assertTrue(brownSummary.contains("wildProgressScore=10002 current=1 need=2"));
            assertTrue(lightBlueSummary.contains("wildProgressScore=33 current=0 need=3"));
        } finally {
            if (previous == null) {
                System.clearProperty("monopoly.ai.includeWildProgressInSummary");
            } else {
                System.setProperty("monopoly.ai.includeWildProgressInSummary", previous);
            }
        }
    }

    @Test
    void hardWildDeployCandidatesPreferFillingIncompleteColorOverOverfullSet() {
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.addToPropertyZone(new PropertyCard("brown-a", "Brown A", "BROWN"));
        bot.addToPropertyZone(new PropertyCard("brown-b", "Brown B", "BROWN"));
        bot.addToPropertyZone(new PropertyCard("light-blue-a", "Light Blue A", "LIGHT_BLUE"));
        PropertyWildCard wild = new PropertyWildCard(
                "wild",
                "Wild",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("BROWN", "LIGHT_BLUE"));
        bot.receiveCardToHand(wild);

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot));

        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, ctx);

        List<String> deployColors = candidates.stream()
                .filter(c -> "DEPLOY".equals(c.request().getActionType()))
                .map(c -> c.request().getTargetColorKey())
                .toList();
        assertEquals(List.of("LIGHT_BLUE", "BROWN"), deployColors);
    }

    private static PlayActionRequest firstStealRequest(AiStrategyProfile profile) {
        RecordingBridge bridge = new RecordingBridge();
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("steal1", "s", "STEAL_PROPERTY"));

        HumanPlayer highThreat = new HumanPlayer("h1", "H1");
        highThreat.addToPropertyZone(new PropertyCard("b1", "b1", "BROWN"));
        highThreat.addToBank(new ActionCard("cashlike", "banked action", "PASS_GO"));

        HumanPlayer highValueLowThreat = new HumanPlayer("h2", "H2");
        highValueLowThreat.addToPropertyZone(new PropertyCard("r1", "r1", "RAILROAD"));

        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(bot, highThreat, highValueLowThreat));

        AiHeuristics.tryPlayOneCard(profile, bot, ctx, bridge);
        return bridge.last;
    }

    private static final class RecordingBridge implements AiGameBridge {
        PlayActionRequest last;

        @Override
        public void submitPlayAction(PlayActionRequest request) {
            this.last = request;
        }
    }
}
