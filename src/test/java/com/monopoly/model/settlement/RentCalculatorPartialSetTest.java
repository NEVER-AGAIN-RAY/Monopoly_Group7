package com.monopoly.model.settlement;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.HumanPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RentCalculatorPartialSetTest {

    @Test
    void incompleteSet_cannotChargeRent() {
        HumanPlayer landlord = new HumanPlayer("p1", "L");
        landlord.addToPropertyZone(new PropertyCard("b1", "brown-1", "BROWN"));
        assertEquals(0, RentCalculator.computeRentForColor(landlord, "BROWN"));
    }

    @Test
    void fullBrownSet_usesTierTable() {
        HumanPlayer landlord = new HumanPlayer("p1", "L");
        landlord.addToPropertyZone(new PropertyCard("b1", "brown-1", "BROWN"));
        landlord.addToPropertyZone(new PropertyCard("b2", "brown-2", "BROWN"));
        assertEquals(2, RentCalculator.computeRentForColor(landlord, "BROWN"));
    }

    @Test
    void fullGreenSet_withOneHouse_addsThree() {
        HumanPlayer landlord = new HumanPlayer("p2", "L2");
        PropertyCard g1 = new PropertyCard("g1", "a", "GREEN");
        PropertyCard g2 = new PropertyCard("g2", "b", "GREEN");
        PropertyCard g3 = new PropertyCard("g3", "c", "GREEN");
        g1.setBuildingLevel(BuildingLevel.HOUSE);
        landlord.addToPropertyZone(g1);
        landlord.addToPropertyZone(g2);
        landlord.addToPropertyZone(g3);
        assertEquals(7 + 3, RentCalculator.computeRentForColor(landlord, "GREEN"));
    }
}
