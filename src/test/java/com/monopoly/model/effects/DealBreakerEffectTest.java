package com.monopoly.model.effects;

import com.monopoly.model.HumanPlayer;
import com.monopoly.model.Player;
import com.monopoly.model.PropertyCard;
import com.monopoly.model.PropertyWildCard;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DealBreakerEffectTest {

    @Test
    void execute_shouldStealCompleteSet_withAssignedWildCard() {
        Player actor = new HumanPlayer("a1", "actor");
        Player target = new HumanPlayer("t1", "target");
        PropertyCard brown1 = new PropertyCard("b1", "Brown-1", "BROWN");
        PropertyWildCard wild = new PropertyWildCard("w1", "Wild");
        wild.setAssignedColorKey("BROWN");
        target.addToPropertyZone(brown1);
        target.addToPropertyZone(wild);

        ActionEffectContext ctx = ActionEffectContext.builder(actor, GameEngineSingleton.getInstance(), List.of(actor, target))
                .target(target)
                .colorKey("BROWN")
                .build();

        ActionEffectResult result = new DealBreakerEffect().execute(ctx);
        assertTrue(result.isSuccess());
        assertTrue(actor.getPropertyCardsView().contains(brown1));
        assertTrue(actor.getPropertyCardsView().contains(wild));
        assertTrue(target.getPropertyCardsView().isEmpty());
    }

    @Test
    void execute_shouldFail_whenTargetMissingOrNoCompleteSet() {
        Player actor = new HumanPlayer("a1", "actor");
        ActionEffectContext noTargetCtx = ActionEffectContext.builder(actor, GameEngineSingleton.getInstance(), List.of(actor))
                .colorKey("BROWN")
                .build();
        ActionEffectResult noTargetResult = new DealBreakerEffect().execute(noTargetCtx);
        assertFalse(noTargetResult.isSuccess());

        Player target = new HumanPlayer("t1", "target");
        target.addToPropertyZone(new PropertyCard("b1", "Brown-1", "BROWN"));
        PropertyWildCard unassignedWild = new PropertyWildCard("w1", "Wild");
        target.addToPropertyZone(unassignedWild);
        ActionEffectContext incompleteCtx = ActionEffectContext.builder(actor, GameEngineSingleton.getInstance(), List.of(actor, target))
                .target(target)
                .colorKey("BROWN")
                .build();
        ActionEffectResult incompleteResult = new DealBreakerEffect().execute(incompleteCtx);
        assertFalse(incompleteResult.isSuccess());
        assertEquals(2, target.getPropertyCardsView().size());
    }
}
