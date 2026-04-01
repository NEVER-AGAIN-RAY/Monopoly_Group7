package com.monopoly.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 胜利规则：合计完整套数 {@code >= 3}（可跨颜色），与 {@code docs/REQ_TRACE.md} 一致。
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
}
