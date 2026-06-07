package com.monopoly.fx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/** Safe JSON accessors shared by JavaFX client collaborators. */
final class FxJson {

    private FxJson() {
    }

    static JsonObject payload(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        return root.has("payload") && root.get("payload").isJsonObject()
                ? root.getAsJsonObject("payload")
                : new JsonObject();
    }

    static String jsonString(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue == null ? "" : defaultValue;
        }
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException ex) {
            return defaultValue == null ? "" : defaultValue;
        }
    }

    static int jsonInt(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    static long jsonLong(JsonObject obj, String key, long defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    static boolean jsonBool(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
