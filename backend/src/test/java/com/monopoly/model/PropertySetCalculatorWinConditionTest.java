package com.monopoly.model;

import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.settlement.PropertySetCalculator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression checks for the win rule: a player wins once the board contains
 * three or more complete property sets. The sets may come from different colors
 * or from repeated sets in the same color.
 */
class PropertySetCalculatorWinConditionTest {

    @Test
    void threeSetsAcrossDifferentColorsCountsAsThree() {
        List<PropertyCard> zone = new ArrayList<>();
        zone.add(new PropertyCard("b1", "b1", "BROWN"));
        zone.add(new PropertyCard("b2", "b2", "BROWN"));
        zone.add(new PropertyCard("lb1", "lb1", "LIGHT_BLUE"));
        zone.add(new PropertyCard("lb2", "lb2", "LIGHT_BLUE"));
        zone.add(new PropertyCard("lb3", "lb3", "LIGHT_BLUE"));
        zone.add(new PropertyCard("r1", "r1", "RAILROAD"));
        zone.add(new PropertyCard("r2", "r2", "RAILROAD"));
        zone.add(new PropertyCard("r3", "r3", "RAILROAD"));
        zone.add(new PropertyCard("r4", "r4", "RAILROAD"));
        assertEquals(3, PropertySetCalculator.countCompletePropertySets(zone));
    }

    @Test
    void threeSetsSingleColorAlsoCountsAsThree() {
        List<PropertyCard> zone = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            zone.add(new PropertyCard("b" + i, "b" + i, "BROWN"));
        }
        assertEquals(3, PropertySetCalculator.countCompletePropertySets(zone));
    }

    @Test
    void twoSetsDoesNotMeetWinThreshold() {
        List<PropertyCard> zone = new ArrayList<>();
        zone.add(new PropertyCard("b1", "b1", "BROWN"));
        zone.add(new PropertyCard("b2", "b2", "BROWN"));
        zone.add(new PropertyCard("lb1", "lb1", "LIGHT_BLUE"));
        zone.add(new PropertyCard("lb2", "lb2", "LIGHT_BLUE"));
        zone.add(new PropertyCard("lb3", "lb3", "LIGHT_BLUE"));
        assertEquals(2, PropertySetCalculator.countCompletePropertySets(zone));
        assertFalse(PropertySetCalculator.countCompletePropertySets(zone) >= 3);
    }

    @Test
    void winThresholdIsGreaterOrEqualToThreeTotalSets() {
        List<PropertyCard> zone = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            zone.add(new PropertyCard("lb" + i, "lb" + i, "LIGHT_BLUE"));
        }
        int total = PropertySetCalculator.countCompletePropertySets(zone);
        assertEquals(3, total);
        assertTrue(total >= 3);
    }

    @Test
    void assignedWildCountsOnlyTowardItsDeclaredColor() {
        List<PropertyCard> zone = new ArrayList<>();
        zone.add(new PropertyCard("red-1", "red-1", "RED"));
        zone.add(new PropertyCard("red-2", "red-2", "RED"));
        zone.add(new PropertyCard("yellow-1", "yellow-1", "YELLOW"));
        PropertyWildCard wild = new PropertyWildCard(
                "wild-red-yellow",
                "wild",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("RED", "YELLOW"));
        wild.setAssignedColorKey("RED");
        zone.add(wild);

        assertEquals(3, PropertySetCalculator.effectiveCountForColor(zone, "RED"));
        assertEquals(1, PropertySetCalculator.effectiveCountForColor(zone, "YELLOW"));
        assertTrue(PropertySetCalculator.hasCompleteSetForColor(zone, "RED"));
        assertFalse(PropertySetCalculator.hasCompleteSetForColor(zone, "YELLOW"));
    }

    @Test
    void unassignedWildDoesNotCreateAccidentalSet() {
        List<PropertyCard> zone = new ArrayList<>();
        zone.add(new PropertyCard("brown-1", "brown-1", "BROWN"));
        zone.add(new PropertyWildCard("wild-any", "wild"));

        assertEquals(1, PropertySetCalculator.effectiveCountForColor(zone, "BROWN"));
        assertFalse(PropertySetCalculator.hasCompleteSetForColor(zone, "BROWN"));
        assertEquals(0, PropertySetCalculator.countCompletePropertySets(zone));
    }

    @Test
    void colorKeysAreTrimmedAndCaseInsensitive() {
        List<PropertyCard> zone = List.of(
                new PropertyCard("utility-1", "utility-1", "utility"),
                new PropertyCard("utility-2", "utility-2", "UTILITY"));

        assertEquals(2, PropertySetCalculator.effectiveCountForColor(zone, " utility "));
        assertTrue(PropertySetCalculator.hasCompleteSetForColor(zone, " utility "));
    }
}
