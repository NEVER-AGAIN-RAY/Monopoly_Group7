package com.monopoly.presentation;

import com.google.gson.JsonObject;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandCardJsonTest {

    @Test
    void action_includesValueM() {
        JsonObject o = HandCardJson.toHandCardObject(new ActionCard("id1", "n", "HOTEL"));
        assertEquals("ACTION", o.get("kind").getAsString());
        assertEquals(4, o.get("valueM").getAsInt());
        assertTrue(o.get("hintZh").getAsString().contains("4M"));
    }

    @Test
    void property_includesValueM_andSetNeed() {
        JsonObject o = HandCardJson.toHandCardObject(new PropertyCard("p1", "n", "BROWN"));
        assertEquals("PROPERTY", o.get("kind").getAsString());
        assertEquals(2, o.get("setNeed").getAsInt());
        assertEquals(2, o.get("valueM").getAsInt());
    }

    @Test
    void anyColorWild_includesZeroValueM() {
        JsonObject o = HandCardJson.toHandCardObject(new PropertyWildCard("w1", "w"));
        assertEquals("WILD", o.get("kind").getAsString());
        assertEquals(0, o.get("valueM").getAsInt());
    }

    @Test
    void dualWild_includesPrintedFaceValueM() {
        PropertyWildCard w = new PropertyWildCard(
                "w1", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR, List.of("RED", "YELLOW"));
        JsonObject o = HandCardJson.toHandCardObject(w);
        assertEquals("WILD", o.get("kind").getAsString());
        assertEquals(3, o.get("valueM").getAsInt());
        assertTrue(o.get("hintZh").getAsString().contains("3M"));
    }

    @Test
    void wild_includesAssignedColorWhenDeclared() {
        PropertyWildCard w = new PropertyWildCard(
                "w2", "w", PropertyWildCard.WildPropertyKind.DUAL_COLOR, List.of("PINK", "ORANGE"));
        w.setAssignedColorKey("orange");
        JsonObject o = HandCardJson.toHandCardObject(w);
        assertEquals("ORANGE", o.get("assignedColorKey").getAsString());
    }

    @Test
    void money_unchanged() {
        JsonObject o = HandCardJson.toHandCardObject(new MoneyCard("m1", "n", 5));
        assertEquals(5, o.get("valueM").getAsInt());
    }

    @Test
    void rentDual_includesPaletteArray() {
        ActionCard ac = new ActionCard("r2", "n", "RENT_DUAL", List.of("PINK", "ORANGE"));
        JsonObject o = HandCardJson.toHandCardObject(ac);
        assertEquals("RENT_DUAL", o.get("effectCode").getAsString());
        assertTrue(o.has("rentPalette"));
        assertEquals(2, o.getAsJsonArray("rentPalette").size());
    }
}
