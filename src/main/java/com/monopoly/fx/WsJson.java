package com.monopoly.fx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gson 工具：信封编码与 JSON 美化。 */
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

    /** 仅美化信封中的 payload 字段（STATE_UPDATE / MY_HAND 等）。 */
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

    /** {@code PLAY} 载荷：放弃免租；可选 {@code paymentCardIds} 指定首笔租金支付用牌。 */
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
