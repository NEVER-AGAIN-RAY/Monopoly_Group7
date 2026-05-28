package com.monopoly.model.settlement;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.PropertyCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RentCalculatorBuildingBonusTest {

    @Test
    void houseAndHotelBonuses_matchHasbroStyleTable() {
        PropertyCard p = new PropertyCard("p", "n", "GREEN");
        p.setBuildingLevel(BuildingLevel.BASE);
        assertEquals(0, RentCalculator.buildingBonusM(p));

        p.setBuildingLevel(BuildingLevel.HOUSE);
        assertEquals(3, RentCalculator.buildingBonusM(p));

        p.setBuildingLevel(BuildingLevel.HOTEL);
        assertEquals(7, RentCalculator.buildingBonusM(p));
    }
}
