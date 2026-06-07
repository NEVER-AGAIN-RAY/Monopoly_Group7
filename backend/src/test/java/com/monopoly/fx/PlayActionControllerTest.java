package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.monopoly.fx.presentation.CardDisplayData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayActionControllerTest {

    @Test
    void choosesDefaultActionFromCardKind() {
        assertEquals("DEPOSIT", PlayActionController.defaultActionForCard(card("m1", "MONEY", "")));
        assertEquals("DEPLOY", PlayActionController.defaultActionForCard(card("p1", "PROPERTY", "")));
        assertEquals("DEPLOY", PlayActionController.defaultActionForCard(card("w1", "WILD", "")));
        assertEquals("ACTION", PlayActionController.defaultActionForCard(card("a1", "ACTION", "PASS_GO")));
    }

    @Test
    void directPayloadOnlyForSimpleActions() {
        Map<String, Object> deposit = PlayActionController.directPlayPayload(card("m1", "MONEY", ""), "DEPOSIT");
        Map<String, Object> deployProperty = PlayActionController.directPlayPayload(card("p1", "PROPERTY", ""), "DEPLOY");

        assertNotNull(deposit);
        assertEquals("DEPOSIT", deposit.get("actionType"));
        assertEquals("m1", deposit.get("cardId"));
        assertNotNull(deployProperty);
        assertNull(PlayActionController.directPlayPayload(card("w1", "WILD", ""), "DEPLOY"));
        assertNull(PlayActionController.directPlayPayload(card("a1", "ACTION", "RENT"), "ACTION"));
    }

    @Test
    void identifiesOptionsThatNeedUserChoice() {
        JsonArray twoOptions = new JsonArray();
        twoOptions.add("{}");
        twoOptions.add("{}");

        assertTrue(PlayActionController.mustChooseOption(card("w1", "WILD", ""), "DEPLOY", new JsonArray()));
        assertTrue(PlayActionController.mustChooseOption(card("r1", "ACTION", "RENT"), "ACTION", new JsonArray()));
        assertTrue(PlayActionController.mustChooseOption(card("s1", "ACTION", "STEAL_PROPERTY"), "ACTION", twoOptions));
        assertFalse(PlayActionController.mustChooseOption(card("p1", "ACTION", "PASS_GO"), "ACTION", new JsonArray()));
    }

    private static CardDisplayData card(String id, String kind, String effect) {
        return new CardDisplayData(
                id,
                kind,
                "card",
                "card",
                "",
                "",
                "",
                "",
                "",
                effect,
                "",
                "",
                List.of(),
                List.of(),
                "",
                null,
                null);
    }
}
