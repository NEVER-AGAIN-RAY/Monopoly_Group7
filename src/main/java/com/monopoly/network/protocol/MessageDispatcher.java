package com.monopoly.network.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;

/**
 * JSON 消息解析与打包：将客户端指令转为内部动作参数，将模型快照序列化为下行消息。
 */
public class MessageDispatcher {

    private final Gson gson = new Gson();

    /** 将下行快照封装为统一 envelope（骨架） */
    public String toJsonBroadcast(GameStateSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "STATE_UPDATE");
        root.add("payload", gson.toJsonTree(snapshot));
        return gson.toJson(root);
    }

    /** 解析客户端上行消息类型与载荷（骨架） */
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
     * 将 PLAY 消息的 payload 反序列化为 {@link PlayActionRequest}（缺失字段保持 null / Gson 默认）。
     */
    public PlayActionRequest parsePlayActionRequest(JsonObject payload) {
        if (payload == null || payload.entrySet().isEmpty()) {
            return new PlayActionRequest();
        }
        return gson.fromJson(payload, PlayActionRequest.class);
    }

    /**
     * 解析 START_SESSION 载荷；payload 为 null 或空对象时返回新实例（字段为 Gson 默认）。
     */
    public StartSessionRequest parseStartSessionRequest(JsonObject payload) {
        if (payload == null || payload.entrySet().isEmpty()) {
            return new StartSessionRequest();
        }
        StartSessionRequest req = gson.fromJson(payload, StartSessionRequest.class);
        return req != null ? req : new StartSessionRequest();
    }

    /** 统一下行信封：{@code { "type": "...", "payload": { ... } }} */
    public String toJsonEnvelope(String messageType, JsonObject payload) {
        JsonObject root = new JsonObject();
        root.addProperty("type", messageType);
        root.add("payload", payload != null ? payload : new JsonObject());
        return gson.toJson(root);
    }

    /** SAVE_GAME_RESULT / LOAD_GAME_RESULT 载荷 */
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
