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

    private static ActionParamContext params(Player target, String targetCardId, String targetZone) {
        return new ActionParamContext("sly", null, target.getPlayerId(), null, targetCardId, null, targetZone);
    }
}
