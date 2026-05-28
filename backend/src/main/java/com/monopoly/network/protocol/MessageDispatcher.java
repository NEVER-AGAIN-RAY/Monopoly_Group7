package com.monopoly.network.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;

/**
 * JSON parse/serialize helpers for the WebSocket protocol.
 */
public class MessageDispatcher {

    private final Gson gson = new Gson();

    /** STATE_UPDATE envelope */
    public String toJsonBroadcast(GameStateSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "STATE_UPDATE");
        root.add("payload", gson.toJsonTree(snapshot));
        return gson.toJson(root);
    }

    /** Reads message type from JSON */
    public String extractMessageType(String json) {
        JsonElement el = gson.fromJson(json, JsonElement.class);
        if (el != null && el.isJsonObject() && el.getAsJsonObject().has("type")) {
            return el.getAsJsonObject().get("type").getAsString();
        }
        return "UNKNOWN";
    }

    public JsonObject parseObject(String json) {
        return gson.fromJson(json, JsonObject.class);
    }

    public JsonObject extractPayload(JsonObject root) {
        if (root == null || !root.has("payload") || !root.get("payload").isJsonObject()) {
            return new JsonObject();
        }
        return root.getAsJsonObject("payload");
    }

    public String extractRequestId(JsonObject root, JsonObject payload) {
        String rootId = getString(root, "requestId", null);
        if (rootId != null && !rootId.isBlank()) {
            return rootId;
        }
        return getString(payload, "requestId", null);
    }

    public int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    public String getString(JsonObject obj, String key, String defaultValue) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    /**
     * Deserializes PLAY payload to PlayActionRequest.
     */
    public PlayActionRequest parsePlayActionRequest(JsonObject payload) {
        if (payload == null || payload.entrySet().isEmpty()) {
            return new PlayActionRequest();
        }
        return gson.fromJson(payload, PlayActionRequest.class);
    }

    /**
     * Parses START_SESSION; empty payload yields defaults.
     */
    public StartSessionRequest parseStartSessionRequest(JsonObject payload) {
        if (payload == null || payload.entrySet().isEmpty()) {
            return new StartSessionRequest();
        }
        StartSessionRequest req = gson.fromJson(payload, StartSessionRequest.class);
        return req != null ? req : new StartSessionRequest();
    }

    /** Outbound envelope: {type, payload}. */
    public String toJsonEnvelope(String messageType, JsonObject payload) {
        JsonObject root = new JsonObject();
        root.addProperty("type", messageType);
        root.add("payload", payload != null ? payload : new JsonObject());
        return gson.toJson(root);
    }

    /**
     * Serializes a DTO as the payload object.
     */
    public String toJsonEnvelopeModel(String messageType, Object payloadModel) {
        JsonObject root = new JsonObject();
        root.addProperty("type", messageType);
        root.add("payload", payloadModel == null ? new JsonObject() : gson.toJsonTree(payloadModel));
        return gson.toJson(root);
    }

    /** SAVE_GAME_RESULT / LOAD_GAME_RESULT payload shape */
    public JsonObject operationResult(boolean ok, String errorMessage) {
        JsonObject p = new JsonObject();
        p.addProperty("ok", ok);
        if (errorMessage != null && !errorMessage.isBlank()) {
            p.addProperty("error", errorMessage);
        }
        return p;
    }

    public JsonObject saveGameResultOkWithJson(String mementoJson) {
        JsonObject p = operationResult(true, null);
        p.addProperty("mementoJson", mementoJson);
        return p;
    }

    public JsonObject saveGameResultOkWithPath(String writtenPath) {
        JsonObject p = operationResult(true, null);
        p.addProperty("writtenPath", writtenPath);
        return p;
    }

    public String toErrorEnvelope(String code, String message, String requestId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code == null || code.isBlank() ? "UNKNOWN_ERROR" : code);
        payload.addProperty("message", message == null || message.isBlank() ? "Unknown error" : message);
        if (requestId != null && !requestId.isBlank()) {
            payload.addProperty("requestId", requestId);
        }
        return toJsonEnvelope("ERROR", payload);
    }
}
