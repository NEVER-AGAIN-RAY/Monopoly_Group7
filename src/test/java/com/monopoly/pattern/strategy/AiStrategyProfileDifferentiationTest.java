package com.monopoly.pattern.strategy;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.dto.PlayActionRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void stealTargetDiffersBetweenEasyAndHardWhenThreatAndValueDiverge() {
        PlayActionRequest easy = firstStealRequest(AiStrategyProfile.EASY);
        PlayActionRequest hard = firstStealRequest(AiStrategyProfile.HARD);
        assertNotEquals(easy.getTargetPlayerId(), hard.getTargetPlayerId());
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
        RecordingBridge bridgeEasy = new RecordingBridge();
        AiHeuristics.tryPlayOneCard(AiStrategyProfile.EASY, bot2, ctx, bridgeEasy);
        assertTrue(bridgeEasy.last != null && "ACTION".equals(bridgeEasy.last.getActionType()));
    }

    private static PlayActionRequest firstStealRequest(AiStrategyProfile profile) {
        RecordingBridge bridge = new RecordingBridge();
        AIPlayer bot = new AIPlayer("ai", "AI", null);
        bot.receiveCardToHand(new ActionCard("steal1", "s", "STEAL_PROPERTY"));

        HumanPlayer highThreat = new HumanPlayer("h1", "H1");
        highThreat.addToPropertyZone(new PropertyCard("b1", "b1", "BROWN"));
        highThreat.addToPropertyZone(new PropertyCard("b2", "b2", "BROWN"));

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
