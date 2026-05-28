package com.monopoly.fx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gson helper for envelope encoding and readable JSON output. */
final class WsJson {

    private static final Gson COMPACT = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private WsJson() {
    }

    static String envelope(String type, Map<String, Object> payload) {
        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        root.add("payload", COMPACT.toJsonTree(payload == null ? Map.of() : payload));
        return COMPACT.toJson(root);
    }

    static String pretty(String rawJson) {
        try {
            JsonElement el = JsonParser.parseString(rawJson);
            return PRETTY.toJson(el);
        } catch (Exception e) {
            return rawJson;
        }
    }

    /** Pretty-print only the payload field inside an envelope such as STATE_UPDATE or MY_HAND. */
    static String payloadPretty(String rawJson) {
        try {
            JsonObject o = JsonParser.parseString(rawJson).getAsJsonObject();
            if (o.has("payload") && !o.get("payload").isJsonNull()) {
                return PRETTY.toJson(o.get("payload"));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return pretty(rawJson);
    }

    static String typeOf(String rawJson) {
        try {
            JsonObject o = JsonParser.parseString(rawJson).getAsJsonObject();
            if (o.has("type") && !o.get("type").isJsonNull()) {
                return o.get("type").getAsString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "";
    }

    static Map<String, Object> playPayload(
            String actionType,
            String cardId,
            String targetPlayerId,
            String targetColorKey,
            String targetCardId,
            String actorCardId,
            String targetZone,
            String actingPlayerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actionType", actionType);
        m.put("cardId", cardId);
        if (notBlank(targetPlayerId)) {
            m.put("targetPlayerId", targetPlayerId.trim());
        }
        if (notBlank(targetColorKey)) {
            m.put("targetColorKey", targetColorKey.trim());
        }
        if (notBlank(targetCardId)) {
            m.put("targetCardId", targetCardId.trim());
        }
        if (notBlank(actorCardId)) {
            m.put("actorCardId", actorCardId.trim());
        }
        if (notBlank(targetZone)) {
            m.put("targetZone", targetZone.trim());
        }
        if (notBlank(actingPlayerId)) {
            m.put("actingPlayerId", actingPlayerId.trim());
        }
        return m;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** PLAY payload for passing on Just Say No, optionally with explicit paymentCardIds. */
    static Map<String, Object> playResponsePass(String actingPlayerId, List<String> paymentCardIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actionType", "RESPONSE_PASS");
        m.put("actingPlayerId", actingPlayerId != null ? actingPlayerId.trim() : "");
        if (paymentCardIds != null && !paymentCardIds.isEmpty()) {
            m.put("paymentCardIds", paymentCardIds);
        }
        return m;
    }
}
