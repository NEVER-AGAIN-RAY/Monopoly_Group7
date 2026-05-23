package com.monopoly.model.card;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void assignedWild_rejectsLaterColorChange() {
        PropertyWildCard w = new PropertyWildCard(
                "w1", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("LIGHT_BLUE", "BROWN"));
        w.setAssignedColorKey("BROWN");

        assertThrows(IllegalStateException.class, () -> w.setAssignedColorKey("LIGHT_BLUE"));
        assertEquals("BROWN", w.getAssignedColorKey());
    }

    @Test
    void assignedWild_acceptsSameColorAgainForIdempotentDeploy() {
        PropertyWildCard w = new PropertyWildCard(
                "w1", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("LIGHT_BLUE", "BROWN"));
        w.setAssignedColorKey("BROWN");

        assertDoesNotThrow(() -> w.setAssignedColorKey("brown"));
        assertEquals("BROWN", w.getAssignedColorKey());
    }

    @Test
    void anyColorWild_hasNoPaymentValue() {
        PropertyWildCard w = new PropertyWildCard("w1", "w");
        assertEquals(0, w.getPaymentValue());
    }

    @Test
    void dualWild_usesPrintedFaceValue() {
        assertEquals(1, new PropertyWildCard(
                "w1", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("LIGHT_BLUE", "BROWN")).getPaymentValue());
        assertEquals(2, new PropertyWildCard(
                "w2", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("PINK", "ORANGE")).getPaymentValue());
        assertEquals(2, new PropertyWildCard(
                "w3", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("RAILROAD", "UTILITY")).getPaymentValue());
        assertEquals(3, new PropertyWildCard(
                "w4", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("RED", "YELLOW")).getPaymentValue());
        assertEquals(4, new PropertyWildCard(
                "w5", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("DARK_BLUE", "GREEN")).getPaymentValue());
        assertEquals(4, new PropertyWildCard(
                "w6", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("LIGHT_BLUE", "RAILROAD")).getPaymentValue());
        assertEquals(4, new PropertyWildCard(
                "w7", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("GREEN", "RAILROAD")).getPaymentValue());
    }
}
