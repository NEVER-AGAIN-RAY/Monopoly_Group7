package com.monopoly.model.settlement;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.HumanPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RentCalculatorPartialSetTest {

    @Test
    void incompleteSet_chargesFirstTierRent() {
        HumanPlayer landlord = new HumanPlayer("p1", "L");
        landlord.addToPropertyZone(new PropertyCard("b1", "brown-1", "BROWN"));
        assertEquals(1, RentCalculator.computeRentForColor(landlord, "BROWN"));
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

    @Test
    void repeatedFullSets_areChargedInSegments() {
        HumanPlayer landlord = new HumanPlayer("p3", "L3");
        for (int i = 0; i < 5; i++) {
            landlord.addToPropertyZone(new PropertyCard("brown-" + i, "brown", "BROWN"));
        }

        assertEquals(5, RentCalculator.computeRentForColor(landlord, "BROWN"));
    }

    @Test
    void assignedWildContributesToRentForDeclaredColorOnly() {
        HumanPlayer landlord = new HumanPlayer("p4", "L4");
        landlord.addToPropertyZone(new PropertyCard("red-1", "red", "RED"));
        landlord.addToPropertyZone(new PropertyCard("yellow-1", "yellow", "YELLOW"));
        PropertyWildCard wild = new PropertyWildCard(
                "wild-red-yellow",
                "wild",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                java.util.List.of("RED", "YELLOW"));
        wild.setAssignedColorKey("RED");
        landlord.addToPropertyZone(wild);

        assertEquals(3, RentCalculator.computeRentForColor(landlord, "RED"));
        assertEquals(2, RentCalculator.computeRentForColor(landlord, "YELLOW"));
    }

    @Test
    void missingOrBlankColorChargesZero() {
        HumanPlayer landlord = new HumanPlayer("p5", "L5");
        landlord.addToPropertyZone(new PropertyCard("red-1", "red", "RED"));

        assertEquals(0, RentCalculator.computeRentForColor(null, "RED"));
        assertEquals(0, RentCalculator.computeRentForColor(landlord, " "));
        assertEquals(0, RentCalculator.computeRentForColor(landlord, "GREEN"));
    }
}
