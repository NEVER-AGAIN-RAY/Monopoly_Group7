package com.monopoly.model.effects;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseHotelEffectTest {

    @Test
    void houseAndHotelAreLimitedToOnePerCompleteSet() {
        Player actor = new HumanPlayer("a", "Actor");
        PropertyCard red1 = new PropertyCard("red-1", "Red 1", "RED");
        PropertyCard red2 = new PropertyCard("red-2", "Red 2", "RED");
        PropertyCard red3 = new PropertyCard("red-3", "Red 3", "RED");
        actor.addToPropertyZone(red1);
        actor.addToPropertyZone(red2);
        actor.addToPropertyZone(red3);

        assertTrue(new HouseEffect().execute(ctx(actor, red1)).isSuccess());
        assertEquals(BuildingLevel.HOUSE, red1.getBuildingLevel());

        ActionEffectResult secondHouse = new HouseEffect().execute(ctx(actor, red2));
        assertFalse(secondHouse.isSuccess());
        assertEquals(BuildingLevel.BASE, red2.getBuildingLevel());

        assertTrue(new HotelEffect().execute(ctx(actor, red1)).isSuccess());
        assertEquals(BuildingLevel.HOTEL, red1.getBuildingLevel());

        red2.setBuildingLevel(BuildingLevel.HOUSE);
        ActionEffectResult secondHotel = new HotelEffect().execute(ctx(actor, red2));
        assertFalse(secondHotel.isSuccess());
        assertEquals(BuildingLevel.HOUSE, red2.getBuildingLevel());
    }

    private static ActionEffectContext ctx(Player actor, PropertyCard property) {
        return ActionEffectContext.builder(actor, GameEngineSingleton.createIsolated(), List.of(actor))
                .actorProperty(property)
                .build();
    }
}
