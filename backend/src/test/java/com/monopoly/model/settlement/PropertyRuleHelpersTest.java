package com.monopoly.model.settlement;

import com.monopoly.dto.PropertyColorCount;
import com.monopoly.dto.PropertyColorProgress;
import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyRuleHelpersTest {

    @Test
    void buildingPlacement_shouldAllowColorGroupsButRejectRailroadUtilityAndBlank() {
        assertTrue(BuildingPlacementRules.allowsHouseHotel("BROWN"));
        assertTrue(BuildingPlacementRules.allowsHouseHotel(" green "));

        assertFalse(BuildingPlacementRules.allowsHouseHotel("RAILROAD"));
        assertFalse(BuildingPlacementRules.allowsHouseHotel("utility"));
        assertFalse(BuildingPlacementRules.allowsHouseHotel(null));
        assertFalse(BuildingPlacementRules.allowsHouseHotel(" "));
    }

    @Test
    void buildingLookup_shouldMatchAssignedWildColorOnly() {
        PropertyCard orange = property("o1", "Orange", "ORANGE");
        orange.setBuildingLevel(BuildingLevel.HOUSE);
        PropertyWildCard wild = anyWild("w1", "Wild", "RED");
        wild.setBuildingLevel(BuildingLevel.HOTEL);
        List<PropertyCard> zone = List.of(orange, wild);

        assertTrue(BuildingPlacementRules.hasAnyBuildingForColor(zone, "orange"));
        assertTrue(BuildingPlacementRules.hasAnyBuildingForColor(zone, "RED"));
        assertTrue(BuildingPlacementRules.hasHotelForColor(zone, "red"));
        assertFalse(BuildingPlacementRules.hasHotelForColor(zone, "orange"));
        assertFalse(BuildingPlacementRules.hasAnyBuildingForColor(zone, "YELLOW"));
    }

    @Test
    void buildingLookup_shouldIgnoreNullZoneBlankColorAndUnassignedWilds() {
        PropertyWildCard unassigned = new PropertyWildCard("w1", "Any Wild");
        unassigned.setBuildingLevel(BuildingLevel.HOUSE);

        assertFalse(BuildingPlacementRules.hasAnyBuildingForColor(null, "BROWN"));
        assertFalse(BuildingPlacementRules.hasAnyBuildingForColor(List.of(unassigned), null));
        assertFalse(BuildingPlacementRules.hasAnyBuildingForColor(List.of(unassigned), "BROWN"));
        assertFalse(BuildingPlacementRules.hasHotelForColor(List.of(unassigned), "BROWN"));
    }

    @Test
    void stealRules_shouldBlockStealingFromCompleteSetWithAssignedWild() {
        Player target = player();
        PropertyCard brown = property("b1", "Brown 1", "BROWN");
        PropertyWildCard wild = anyWild("w1", "Any Wild", "BROWN");
        target.addToPropertyZone(brown);
        target.addToPropertyZone(wild);

        assertFalse(PropertyStealRules.mayStealPropertyFromTarget(target, brown));
        assertFalse(PropertyStealRules.mayStealPropertyFromTarget(target, wild));
    }

    @Test
    void stealRules_shouldAllowIncompleteSetAndUnassignedWild() {
        Player target = player();
        PropertyCard red = property("r1", "Red 1", "RED");
        PropertyWildCard unassigned = new PropertyWildCard("w1", "Any Wild");
        target.addToPropertyZone(red);
        target.addToPropertyZone(unassigned);

        assertTrue(PropertyStealRules.mayStealPropertyFromTarget(target, red));
        assertTrue(PropertyStealRules.mayStealPropertyFromTarget(target, unassigned));
        assertFalse(PropertyStealRules.mayStealPropertyFromTarget(null, red));
        assertFalse(PropertyStealRules.mayStealPropertyFromTarget(target, null));
    }

    @Test
    void zoneSummary_shouldCountAssignedWildsAndUnassignedWildsSeparately() {
        PropertyCard brown = property("b1", "Brown 1", "brown");
        PropertyCard unknown = property("u1", "Unknown", "");
        PropertyWildCard assigned = anyWild("w1", "Any Wild", "BROWN");
        PropertyWildCard unassigned = new PropertyWildCard("w2", "Any Wild 2");

        List<PropertyColorCount> summary = PropertyZoneSummary.summarizeByColor(
                List.of(unassigned, brown, assigned, unknown));

        Map<String, Integer> counts = summary.stream()
                .collect(Collectors.toMap(PropertyColorCount::getColorKey, PropertyColorCount::getCount));
        assertEquals(2, counts.get("BROWN"));
        assertEquals(1, counts.get("UNKNOWN"));
        assertEquals(1, counts.get(PropertyZoneSummary.WILD_UNASSIGNED_COLOR_KEY));
    }

    @Test
    void zoneSummary_shouldReturnSortedColorKeysForStableUiSnapshots() {
        List<PropertyColorCount> summary = PropertyZoneSummary.summarizeByColor(List.of(
                property("r1", "Red", "RED"),
                property("b1", "Brown", "BROWN"),
                property("g1", "Green", "GREEN")));

        assertEquals(List.of("BROWN", "GREEN", "RED"), summary.stream()
                .map(PropertyColorCount::getColorKey)
                .toList());
    }

    @Test
    void colorProgress_shouldReportEffectiveCountsNeedsAndCompleteSets() {
        PropertyCard brown1 = property("b1", "Brown 1", "BROWN");
        PropertyCard brown2 = property("b2", "Brown 2", "BROWN");
        PropertyWildCard brownWild = anyWild("w1", "Any Wild", "BROWN");
        PropertyWildCard unassigned = new PropertyWildCard("w2", "Any Wild 2");

        List<PropertyColorProgress> progress = PropertyZoneSummary.colorProgress(
                List.of(brown1, brown2, brownWild, unassigned));

        PropertyColorProgress brown = findProgress(progress, "BROWN");
        assertEquals(3, brown.getEffectiveCount());
        assertEquals(2, brown.getNeed());
        assertEquals(1, brown.getCompleteSets());

        PropertyColorProgress wild = findProgress(progress, PropertyZoneSummary.WILD_UNASSIGNED_COLOR_KEY);
        assertEquals(1, wild.getEffectiveCount());
        assertEquals(0, wild.getNeed());
        assertEquals(0, wild.getCompleteSets());
    }

    @Test
    void rentTierTable_shouldUseOfficialTiersAndNormalizeColorKeys() {
        assertEquals(List.of(1, 2), toList(RentTierTable.tiersForColor(" brown ")));
        assertEquals(List.of(2, 4, 7), toList(RentTierTable.tiersForColor("GREEN")));
        assertEquals(List.of(1, 2, 3, 7), toList(RentTierTable.tiersForColor("railroad")));
        assertEquals(List.of(1, 2, 3), toList(RentTierTable.tiersForColor("CUSTOM_COLOR")));
        assertEquals(List.of(), toList(RentTierTable.tiersForColor(" ")));
    }

    @Test
    void rentTierTable_shouldSupportMultipleCompleteSetsForSameColor() {
        assertEquals(0, RentTierTable.baseRentForPropertyCount("BROWN", 0));
        assertEquals(1, RentTierTable.baseRentForPropertyCount("BROWN", 1));
        assertEquals(2, RentTierTable.baseRentForPropertyCount("BROWN", 2));
        assertEquals(3, RentTierTable.baseRentForPropertyCount("BROWN", 3));
        assertEquals(4, RentTierTable.baseRentForPropertyCount("BROWN", 4));

        assertEquals(7, RentTierTable.baseRentForPropertyCount("RAILROAD", 4));
        assertEquals(8, RentTierTable.baseRentForPropertyCount("RAILROAD", 5));
    }

    private static PropertyColorProgress findProgress(List<PropertyColorProgress> progress, String colorKey) {
        return progress.stream()
                .filter(row -> colorKey.equals(row.getColorKey()))
                .findFirst()
                .orElseThrow();
    }

    private static List<Integer> toList(int[] values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }

    private static PropertyWildCard anyWild(String id, String name, String assignedColor) {
        PropertyWildCard wild = new PropertyWildCard(id, name);
        wild.setAssignedColorKey(assignedColor);
        return wild;
    }

    private static PropertyCard property(String id, String name, String color) {
        return new PropertyCard(id, name, color);
    }

    private static Player player() {
        return new HumanPlayer("p1", "Player");
    }
}
