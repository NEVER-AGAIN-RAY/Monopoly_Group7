package com.monopoly.fx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxJsonTest {

    @Test
    void payloadReturnsEnvelopePayloadObject() {
        JsonObject payload = FxJson.payload("""
                {"type":"STATE_UPDATE","payload":{"sessionId":"demo","roundNumber":2}}
                """);

        assertEquals("demo", FxJson.jsonString(payload, "sessionId", ""));
        assertEquals(2, FxJson.jsonInt(payload, "roundNumber", 0));
    }

    @Test
    void safeAccessorsReturnFallbacksForMissingNullAndWrongTypes() {
        JsonObject json = JsonParser.parseString("""
                {"name":null,"count":"not-a-number","flag":"not-a-bool"}
                """).getAsJsonObject();

        assertEquals("fallback", FxJson.jsonString(json, "name", "fallback"));
        assertEquals(7, FxJson.jsonInt(json, "count", 7));
        assertEquals(9L, FxJson.jsonLong(json, "missing", 9L));
        assertFalse(FxJson.jsonBool(json, "flag", false));
        assertTrue(FxJson.jsonBool(json, "missing", true));
    }

    @Test
    void normalizesStringsForProtocolHelpers() {
        assertEquals("RENT_DUAL", FxJson.safeUpper(" rent_dual "));
        assertNull(FxJson.blankToNull(""));
        assertEquals("card-1", FxJson.blankToNull("card-1"));
    }
}
