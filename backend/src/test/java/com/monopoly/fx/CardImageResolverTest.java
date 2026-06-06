package com.monopoly.fx;

import com.monopoly.fx.presentation.CardDisplayData;
import com.monopoly.fx.presentation.CardImageResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardImageResolverTest {

    @Test
    void greenRailroadWildRotatesWhenAssignedToRailroad() {
        CardDisplayData card = dualWild("wild-green-railroad", "GREEN", "RAILROAD");

        assertFalse(CardImageResolver.shouldRotateWildImage(card, "GREEN"));
        assertTrue(CardImageResolver.shouldRotateWildImage(card, "RAILROAD"));
    }

    @Test
    void lightBlueBrownWildRotatesWhenAssignedToLightBlue() {
        CardDisplayData card = dualWild("wild-light-blue-brown", "LIGHT_BLUE", "BROWN");

        assertFalse(CardImageResolver.shouldRotateWildImage(card, "BROWN"));
        assertTrue(CardImageResolver.shouldRotateWildImage(card, "LIGHT_BLUE"));
    }

    private static CardDisplayData dualWild(String id, String firstColor, String secondColor) {
        return new CardDisplayData(
                id,
                "WILD",
                "双色房产",
                "Dual-color property",
                "",
                "",
                "",
                "",
                "",
                "",
                "DUAL_COLOR",
                "",
                List.of(firstColor, secondColor),
                List.of(),
                "",
                null,
                null);
    }
}
