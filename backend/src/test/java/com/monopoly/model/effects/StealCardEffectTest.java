package com.monopoly.model.effects;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StealCardEffectTest {

    @Test
    void stealPropertyMovesCardToActorPropertyZone() {
        Player actor = new HumanPlayer("a", "Actor");
        Player target = new HumanPlayer("t", "Target");
        PropertyWildCard wild = new PropertyWildCard(
                "wild-red-yellow",
                "Red Yellow Wild",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("RED", "YELLOW"));
        wild.setAssignedColorKey("YELLOW");
        target.addToPropertyZone(wild);

        ActionEffectContext ctx = ActionEffectContext
                .builder(actor, GameEngineSingleton.createIsolated(), List.of(actor, target))
                .target(target)
                .targetProperty(wild)
                .build();

        ActionEffectResult result = new StealCardEffect().execute(ctx);

        assertTrue(result.isSuccess());
        assertFalse(target.getPropertyCardsView().contains(wild));
        assertSame(wild, actor.getPropertyCardsView().get(0));
        assertEquals("YELLOW", wild.getAssignedColorKey());
        assertTrue(actor.getHandCardsView().isEmpty());
    }

    @Test
    void stealPropertyRejectsCardsInCompleteSet() {
        Player actor = new HumanPlayer("a", "Actor");
        Player target = new HumanPlayer("t", "Target");
        PropertyCard brown1 = new PropertyCard("brown-1", "Brown 1", "BROWN");
        PropertyCard brown2 = new PropertyCard("brown-2", "Brown 2", "BROWN");
        target.addToPropertyZone(brown1);
        target.addToPropertyZone(brown2);

        ActionEffectContext ctx = ActionEffectContext
                .builder(actor, GameEngineSingleton.createIsolated(), List.of(actor, target))
                .target(target)
                .targetProperty(brown1)
                .build();

        ActionEffectResult result = new StealCardEffect().execute(ctx);

        assertFalse(result.isSuccess());
        assertTrue(target.getPropertyCardsView().contains(brown1));
        assertTrue(target.getPropertyCardsView().contains(brown2));
        assertTrue(actor.getPropertyCardsView().isEmpty());
    }
}
