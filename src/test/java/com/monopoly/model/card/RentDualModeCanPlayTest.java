package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.HumanPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RentDualModeCanPlayTest {

    @Test
    void rentDual1v1_requiresTargetPlayer() {
        HumanPlayer actor = new HumanPlayer("a", "A");
        for (int i = 0; i < 3; i++) {
            actor.addToPropertyZone(new PropertyCard("p" + i, "n", "BROWN"));
        }
        ActionCard card = new ActionCard(
                "r1", "r", "RENT_DUAL",
                1,
                List.of("BROWN", "LIGHT_BLUE"),
                false,
                false);
        HumanPlayer other = new HumanPlayer("b", "B");
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(actor, other));

        ActionParamContext noTarget = new ActionParamContext(
                "r1", null, null, "BROWN", null, null, null);
        assertFalse(card.canPlay(actor, noTarget, ctx));

        ActionParamContext ok = new ActionParamContext(
                "r1", null, "b", "BROWN", null, null, null);
        assertTrue(card.canPlay(actor, ok, ctx));
    }

    @Test
    void rentDualAllOthers_doesNotRequireTargetPlayerId() {
        HumanPlayer actor = new HumanPlayer("a", "A");
        for (int i = 0; i < 3; i++) {
            actor.addToPropertyZone(new PropertyCard("p" + i, "n", "BROWN"));
        }
        ActionCard card = new ActionCard(
                "r1", "r", "RENT_DUAL",
                1,
                List.of("BROWN", "LIGHT_BLUE"),
                true,
                false);
        HumanPlayer other = new HumanPlayer("b", "B");
        GameContext ctx = new GameContext();
        ctx.bindPlayers(List.of(actor, other));

        ActionParamContext p = new ActionParamContext(
                "r1", null, null, "BROWN", null, null, null);
        assertTrue(card.canPlay(actor, p, ctx));
    }
}
