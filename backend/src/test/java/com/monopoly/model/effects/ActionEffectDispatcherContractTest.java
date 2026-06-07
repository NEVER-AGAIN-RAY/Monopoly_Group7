package com.monopoly.model.effects;

import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEffectDispatcherContractTest {

    @Test
    void isKnown_shouldAcceptRegisteredEffectCodesCaseInsensitively() {
        assertTrue(ActionEffectDispatcher.isKnown("RENT"));
        assertTrue(ActionEffectDispatcher.isKnown(" rent "));
        assertTrue(ActionEffectDispatcher.isKnown("RENT_DUAL"));
        assertTrue(ActionEffectDispatcher.isKnown("DOUBLE_RENT"));
        assertTrue(ActionEffectDispatcher.isKnown("STEAL_PROPERTY"));
        assertTrue(ActionEffectDispatcher.isKnown("FORCED_DEAL"));
        assertTrue(ActionEffectDispatcher.isKnown("DEBT_COLLECTOR"));
        assertTrue(ActionEffectDispatcher.isKnown("RENT_WAIVER"));
        assertTrue(ActionEffectDispatcher.isKnown("PASS_GO"));
        assertTrue(ActionEffectDispatcher.isKnown("HOUSE"));
        assertTrue(ActionEffectDispatcher.isKnown("HOTEL"));
        assertTrue(ActionEffectDispatcher.isKnown("BIRTHDAY"));
        assertTrue(ActionEffectDispatcher.isKnown("DEAL_BREAKER"));
        assertTrue(ActionEffectDispatcher.isKnown("EFFECT_PLACEHOLDER"));
    }

    @Test
    void isKnown_shouldRejectNullBlankAndUnknownCodes() {
        assertFalse(ActionEffectDispatcher.isKnown(null));
        assertFalse(ActionEffectDispatcher.isKnown(""));
        assertFalse(ActionEffectDispatcher.isKnown("NOT_A_REAL_EFFECT"));
    }

    @Test
    void dispatch_shouldRejectEmptyOrUnknownCodesWithFailedResult() {
        ActionEffectContext ctx = context(player("p1", "Actor"), null);

        ActionEffectResult empty = ActionEffectDispatcher.dispatch(" ", ctx);
        assertEquals(ActionEffectResult.Status.FAILED, empty.getStatus());
        assertTrue(empty.getMessage().contains("empty"));

        ActionEffectResult unknown = ActionEffectDispatcher.dispatch("NOPE", ctx);
        assertEquals(ActionEffectResult.Status.FAILED, unknown.getStatus());
        assertTrue(unknown.getMessage().contains("NOPE"));
    }

    @Test
    void dispatch_shouldRouteRentWaiverAsCountered() {
        Player actor = player("p1", "Defender");

        ActionEffectResult result = ActionEffectDispatcher.dispatch("rent_waiver", context(actor, null));

        assertEquals(ActionEffectResult.Status.COUNTERED, result.getStatus());
        assertTrue(result.getMessage().contains("Defender"));
    }

    @Test
    void dispatch_shouldKeepTurnFlowManagedEffectsOutOfGenericExecution() {
        ActionEffectContext ctx = context(player("p1", "Actor"), player("p2", "Target"));

        ActionEffectResult dualRent = ActionEffectDispatcher.dispatch("RENT_DUAL", ctx);
        assertEquals(ActionEffectResult.Status.FAILED, dualRent.getStatus());
        assertTrue(dualRent.getMessage().contains("effect stack"));

        ActionEffectResult doubleRent = ActionEffectDispatcher.dispatch("DOUBLE_RENT", ctx);
        assertEquals(ActionEffectResult.Status.FAILED, doubleRent.getStatus());
        assertTrue(doubleRent.getMessage().contains("multiplier"));
    }

    @Test
    void dispatch_shouldRoutePlaceholderAsNoOpSuccess() {
        ActionEffectResult result = ActionEffectDispatcher.dispatch(
                "effect_placeholder",
                context(player("p1", "Actor"), null));

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("Placeholder"));
    }

    @Test
    void dispatch_shouldRouteDebtCollectorAndMovePayment() {
        Player actor = player("p1", "Collector");
        Player target = player("p2", "Debtor");
        MoneyCard m5 = new MoneyCard("m5", "5M", 5);
        target.addToBank(m5);

        ActionEffectResult result = ActionEffectDispatcher.dispatch("debt_collector", context(actor, target));

        assertTrue(result.isSuccess());
        assertTrue(actor.getBankCardsView().contains(m5));
        assertEquals(0, target.getBankCardCount());
    }

    @Test
    void dispatch_shouldRouteRentAndHonorContextColor() {
        Player landlord = player("p1", "Landlord");
        Player tenant = player("p2", "Tenant");
        PropertyCard brown1 = new PropertyCard("b1", "Brown 1", "BROWN");
        PropertyCard brown2 = new PropertyCard("b2", "Brown 2", "BROWN");
        MoneyCard payment = new MoneyCard("m2", "2M", 2);
        landlord.addToPropertyZone(brown1);
        landlord.addToPropertyZone(brown2);
        tenant.addToBank(payment);

        ActionEffectContext ctx = ActionEffectContext.builder(landlord, GameEngineSingleton.createIsolated(), List.of(landlord, tenant))
                .target(tenant)
                .colorKey("brown")
                .build();
        ActionEffectResult result = ActionEffectDispatcher.dispatch("RENT", ctx);

        assertTrue(result.isSuccess());
        assertTrue(landlord.getBankCardsView().contains(payment));
        assertEquals(0, tenant.getBankCardCount());
    }

    private static ActionEffectContext context(Player actor, Player target) {
        List<Player> players = target == null ? List.of(actor) : List.of(actor, target);
        return ActionEffectContext.builder(actor, GameEngineSingleton.createIsolated(), players)
                .target(target)
                .build();
    }

    private static Player player(String id, String name) {
        return new HumanPlayer(id, name);
    }
}
