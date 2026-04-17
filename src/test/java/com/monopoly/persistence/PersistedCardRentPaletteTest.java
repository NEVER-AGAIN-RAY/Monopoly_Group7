package com.monopoly.persistence;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.PropertyWildCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedCardRentPaletteTest {

    @Test
    void rentDual_palette_roundTripsThroughPersistedCard() {
        ActionCard original = new ActionCard("ac1", "n", "RENT_DUAL", List.of("LIGHT_BLUE", "BROWN"));
        PersistedCard p = PersistedCard.fromCard(original);
        assertEquals("LIGHT_BLUE|BROWN", p.getRentPalette());
        ActionCard back = (ActionCard) PersistedCard.toCard(p);
        assertEquals("RENT_DUAL", back.getEffectCode());
        assertEquals(List.of("LIGHT_BLUE", "BROWN"), back.getRentPaletteView());
    }

    @Test
    void rentWildcard_flag_roundTrips() {
        ActionCard a = new ActionCard(
                "a1", "n", "RENT",
                com.monopoly.model.rules.MonopolyDealBankValues.bankValueForActionEffect("RENT"),
                List.of(),
                false,
                true);
        ActionCard b = (ActionCard) PersistedCard.toCard(PersistedCard.fromCard(a));
        assertTrue(b.isWildcardRentCard());
    }

    @Test
    void rentDual_eachOtherFlag_roundTrips() {
        ActionCard a = new ActionCard(
                "a2", "n", "RENT_DUAL",
                com.monopoly.model.rules.MonopolyDealBankValues.bankValueForActionEffect("RENT_DUAL"),
                List.of("BROWN", "LIGHT_BLUE"),
                true,
                false);
        ActionCard b = (ActionCard) PersistedCard.toCard(PersistedCard.fromCard(a));
        assertTrue(b.isRentDualChargesEachOtherPlayer());
    }

    @Test
    void wildDual_kind_roundTrips() {
        PropertyWildCard w = new PropertyWildCard(
                "w1", "w",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("PINK", "ORANGE"));
        w.setAssignedColorKey("PINK");
        PersistedCard p = PersistedCard.fromCard(w);
        PropertyWildCard back = (PropertyWildCard) PersistedCard.toCard(p);
        assertEquals(PropertyWildCard.WildPropertyKind.DUAL_COLOR, back.getWildPropertyKind());
        assertEquals("PINK", back.getAssignedColorKey());
    }
}
