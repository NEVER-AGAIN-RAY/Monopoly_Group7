package com.monopoly.model.card;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropertyWildCardKindTest {

    @Test
    void dualWild_rejectsColorNotOnCard() {
        PropertyWildCard w = new PropertyWildCard(
                "w1", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("LIGHT_BLUE", "BROWN"));
        assertThrows(IllegalArgumentException.class, () -> w.setAssignedColorKey("PINK"));
    }

    @Test
    void dualWild_acceptsPrintedColor() {
        PropertyWildCard w = new PropertyWildCard(
                "w1", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("LIGHT_BLUE", "BROWN"));
        assertDoesNotThrow(() -> w.setAssignedColorKey("BROWN"));
    }

    @Test
    void anyWild_acceptsStandardColor() {
        PropertyWildCard w = new PropertyWildCard("w1", "w");
        assertDoesNotThrow(() -> w.setAssignedColorKey("RAILROAD"));
    }
}
