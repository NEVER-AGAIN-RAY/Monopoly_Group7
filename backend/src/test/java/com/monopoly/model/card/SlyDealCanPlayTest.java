package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlyDealCanPlayTest {

    @Test
    void slyDealCanOnlyStealEligiblePropertyCards() {
        Player actor = new HumanPlayer("a", "Actor");
        Player target = new HumanPlayer("t", "Target");
        ActionCard sly = new ActionCard("sly", "Sly Deal", "STEAL_PROPERTY");
        PropertyCard loneRed = new PropertyCard("red-1", "Red", "RED");
        PropertyCard brown1 = new PropertyCard("brown-1", "Brown 1", "BROWN");
        PropertyCard brown2 = new PropertyCard("brown-2", "Brown 2", "BROWN");
        target.addToPropertyZone(loneRed);
        target.addToPropertyZone(brown1);
        target.addToPropertyZone(brown2);
        target.addToBank(new MoneyCard("bank-1", "1M", 1));

        GameContext context = new GameContext();
        context.bindPlayers(List.of(actor, target));

        assertTrue(sly.canPlay(actor, params(target, loneRed.getId(), "PROPERTY"), context));
        assertFalse(sly.canPlay(actor, params(target, brown1.getId(), "PROPERTY"), context));
        assertFalse(sly.canPlay(actor, params(target, "bank-1", "BANK"), context));
    }

    @Test
    void forcedDealRequiresEligiblePropertiesOnBothSides() {
        Player actor = new HumanPlayer("a", "Actor");
        Player target = new HumanPlayer("t", "Target");
        ActionCard forcedDeal = new ActionCard("forced", "Forced Deal", "FORCED_DEAL");
        PropertyCard actorRed = new PropertyCard("actor-red", "Red", "RED");
        PropertyCard targetGreen = new PropertyCard("target-green", "Green", "GREEN");
        PropertyCard targetBrown1 = new PropertyCard("brown-1", "Brown 1", "BROWN");
        PropertyCard targetBrown2 = new PropertyCard("brown-2", "Brown 2", "BROWN");
        actor.addToPropertyZone(actorRed);
        target.addToPropertyZone(targetGreen);
        target.addToPropertyZone(targetBrown1);
        target.addToPropertyZone(targetBrown2);

        GameContext context = new GameContext();
        context.bindPlayers(List.of(actor, target));

        assertTrue(forcedDeal.canPlay(
                actor,
                new ActionParamContext("forced", null, target.getPlayerId(), null,
                        targetGreen.getId(), actorRed.getId(), null),
                context));
        assertFalse(forcedDeal.canPlay(
                actor,
                new ActionParamContext("forced", null, target.getPlayerId(), null,
                        targetBrown1.getId(), actorRed.getId(), null),
                context));
        assertFalse(forcedDeal.canPlay(
                actor,
                new ActionParamContext("forced", null, target.getPlayerId(), null,
                        targetGreen.getId(), null, null),
                context));
    }

    @Test
    void dealBreakerRequiresTargetCompleteSet() {
        Player actor = new HumanPlayer("a", "Actor");
        Player target = new HumanPlayer("t", "Target");
        ActionCard dealBreaker = new ActionCard("deal-breaker", "Deal Breaker", "DEAL_BREAKER");
        PropertyCard brown1 = new PropertyCard("brown-1", "Brown 1", "BROWN");
        PropertyCard brown2 = new PropertyCard("brown-2", "Brown 2", "BROWN");
        PropertyCard red1 = new PropertyCard("red-1", "Red 1", "RED");
        target.addToPropertyZone(brown1);
        target.addToPropertyZone(brown2);
        target.addToPropertyZone(red1);

        GameContext context = new GameContext();
        context.bindPlayers(List.of(actor, target));

        assertTrue(dealBreaker.canPlay(
                actor,
                new ActionParamContext("deal-breaker", null, target.getPlayerId(), "BROWN",
                        null, null, null),
                context));
        assertTrue(dealBreaker.canPlay(
                actor,
                new ActionParamContext("deal-breaker", null, target.getPlayerId(), null,
                        brown1.getId(), null, null),
                context));
        assertFalse(dealBreaker.canPlay(
                actor,
                new ActionParamContext("deal-breaker", null, target.getPlayerId(), "RED",
                        null, null, null),
                context));
        assertFalse(dealBreaker.canPlay(
                actor,
                new ActionParamContext("deal-breaker", null, null, "BROWN",
                        null, null, null),
                context));
    }

    private static ActionParamContext params(Player target, String targetCardId, String targetZone) {
        return new ActionParamContext("sly", null, target.getPlayerId(), null, targetCardId, null, targetZone);
    }
}
