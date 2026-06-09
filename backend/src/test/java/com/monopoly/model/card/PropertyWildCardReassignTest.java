package com.monopoly.model.card;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropertyWildCardReassignTest {

    @Test
    void reassignColorKey_anyColor_success() {
        PropertyWildCard w = new PropertyWildCard("w1", "w");
        w.setAssignedColorKey("BROWN");
        w.reassignColorKey("GREEN");
        assertEquals("GREEN", w.getAssignedColorKey());
    }

    @Test
    void reassignColorKey_dualColor_success() {
        PropertyWildCard w = new PropertyWildCard(
                "w1", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("LIGHT_BLUE", "BROWN"));
        w.setAssignedColorKey("BROWN");
        w.reassignColorKey("LIGHT_BLUE");
        assertEquals("LIGHT_BLUE", w.getAssignedColorKey());
    }

    @Test
    void reassignColorKey_dualColor_rejectsNonPrintedColor() {
        PropertyWildCard w = new PropertyWildCard(
                "w1", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("LIGHT_BLUE", "BROWN"));
        w.setAssignedColorKey("BROWN");
        assertThrows(IllegalArgumentException.class, () -> w.reassignColorKey("PINK"));
        assertEquals("BROWN", w.getAssignedColorKey());
    }

    @Test
    void reassignColorKey_rejectsBlank() {
        PropertyWildCard w = new PropertyWildCard("w1", "w");
        w.setAssignedColorKey("BROWN");
        assertThrows(IllegalArgumentException.class, () -> w.reassignColorKey(""));
        assertThrows(IllegalArgumentException.class, () -> w.reassignColorKey("  "));
    }

    @Test
    void reassignColorKey_rejectsInvalidColorKey() {
        PropertyWildCard w = new PropertyWildCard("w1", "w");
        w.setAssignedColorKey("BROWN");
        assertThrows(IllegalArgumentException.class, () -> w.reassignColorKey("NOT_A_COLOR"));
    }

    @Test
    void reassignColorKey_canReassignMultipleTimes() {
        PropertyWildCard w = new PropertyWildCard("w1", "w");
        w.setAssignedColorKey("BROWN");
        w.reassignColorKey("GREEN");
        assertEquals("GREEN", w.getAssignedColorKey());
        w.reassignColorKey("RAILROAD");
        assertEquals("RAILROAD", w.getAssignedColorKey());
    }
}
