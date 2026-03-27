package com.monopoly.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.model.dto.GameStateSnapshot;

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
}
