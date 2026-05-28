package com.monopoly.model.effects;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedDealEffectTest {

    @Test
    void swappedPropertiesMoveBetweenPropertyZones() {
        Player actor = new HumanPlayer("a", "Actor");
        Player target = new HumanPlayer("t", "Target");
        PropertyCard actorProperty = new PropertyCard("actor-red", "Actor Red", "RED");
        PropertyCard targetProperty = new PropertyCard("target-blue", "Target Blue", "LIGHT_BLUE");
        actor.addToPropertyZone(actorProperty);
        target.addToPropertyZone(targetProperty);

        ActionEffectContext ctx = ActionEffectContext
                .builder(actor, GameEngineSingleton.getInstance(), List.of(actor, target))
                .target(target)
                .actorProperty(actorProperty)
                .targetProperty(targetProperty)
                .build();

        ActionEffectResult result = new ForcedDealEffect().execute(ctx);

        assertTrue(result.isSuccess());
        assertTrue(actor.getPropertyCardsView().contains(targetProperty));
        assertTrue(target.getPropertyCardsView().contains(actorProperty));
        assertFalse(actor.getPropertyCardsView().contains(actorProperty));
        assertFalse(target.getPropertyCardsView().contains(targetProperty));
        assertEquals(0, actor.getHandCardsView().size());
        assertEquals(0, target.getHandCardsView().size());
    }

    @Test
    void forcedDealCannotTradeCompleteSetProperty() {
        Player actor = new HumanPlayer("a", "Actor");
        Player target = new HumanPlayer("t", "Target");
        PropertyCard actorProperty = new PropertyCard("actor-red", "Actor Red", "RED");
        PropertyCard targetBrown1 = new PropertyCard("target-brown-1", "Target Brown 1", "BROWN");
        PropertyCard targetBrown2 = new PropertyCard("target-brown-2", "Target Brown 2", "BROWN");
        actor.addToPropertyZone(actorProperty);
        target.addToPropertyZone(targetBrown1);
        target.addToPropertyZone(targetBrown2);

        ActionEffectContext ctx = ActionEffectContext
                .builder(actor, GameEngineSingleton.getInstance(), List.of(actor, target))
                .target(target)
                .actorProperty(actorProperty)
                .targetProperty(targetBrown1)
                .build();

        ActionEffectResult result = new ForcedDealEffect().execute(ctx);

        assertFalse(result.isSuccess());
        assertTrue(actor.getPropertyCardsView().contains(actorProperty));
        assertTrue(target.getPropertyCardsView().contains(targetBrown1));
        assertTrue(target.getPropertyCardsView().contains(targetBrown2));
    }
}
