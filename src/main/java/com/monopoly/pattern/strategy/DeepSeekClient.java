package com.monopoly.pattern.strategy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DeepSeek OpenAI-compatible chat client.
 */
public final class DeepSeekClient {

    private static final Gson GSON = new Gson();
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final String DEFAULT_FALLBACK_MODEL = "deepseek-chat";
    private static final AtomicInteger PREFERRED_JSON_FAILURES = new AtomicInteger();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
            .build();

    String complete(String systemPrompt, String userPrompt, boolean strictJson)
            throws IOException, InterruptedException {
        if (strictJson && preferFallbackForStrictJson()) {
            AiBattleLogger.log("DeepSeek",
                    "strict JSON configured for fallback; using fallback model=" + fallbackModel()
                            + " preferred=" + model());
            return completeFallback(systemPrompt, userPrompt, true);
        }
        if (strictJson && preferredJsonCircuitOpen()) {
            AiBattleLogger.log("DeepSeek",
                    "preferred JSON circuit open; using fallback model=" + fallbackModel());
            return completeFallback(systemPrompt, userPrompt, true);
        }
        try {
            return completePreferred(systemPrompt, userPrompt, strictJson, model());
        } catch (IOException ex) {
            if (strictJson) {
                recordPreferredJsonFailure(ex.getMessage());
            }
            throw ex;
        }
    }

    String completeFallback(String systemPrompt, String userPrompt, boolean strictJson)
            throws IOException, InterruptedException {
        return completeWithModel(fallbackModel(), systemPrompt, userPrompt, strictJson);
    }

    void recordPreferredJsonFailure(String reason) {
        int failures = PREFERRED_JSON_FAILURES.incrementAndGet();
        AiBattleLogger.log("DeepSeek",
                "preferred JSON failure " + failures + "/" + jsonFailureThreshold()
                        + ": " + reason);
        if (failures == jsonFailureThreshold()) {
            AiBattleLogger.log("DeepSeek",
                    "preferred JSON circuit opened; future strict JSON calls use fallback model="
                            + fallbackModel());
        }
    }

    void recordPreferredJsonSuccess() {
        // Keep accumulated failures. V4 flash can alternate valid and malformed JSON;
        // resetting here would repeatedly reopen a costly unstable path.
    }

    boolean willUsePreferredForStrictJson() {
        return !preferFallbackForStrictJson() && !preferredJsonCircuitOpen();
    }

    private String completePreferred(
            String systemPrompt,
            String userPrompt,
            boolean strictJson,
            String preferredModel) throws IOException, InterruptedException {
        try {
            return completeWithModel(preferredModel, systemPrompt, userPrompt, strictJson);
        } catch (IOException first) {
            String fallback = fallbackModel();
            if (fallback.equals(preferredModel)) {
                throw first;
            }
            AiBattleLogger.log("DeepSeek",
                    "model fallback " + preferredModel + " -> " + fallback
                            + " after " + first.getMessage());
            return completeWithModel(fallback, systemPrompt, userPrompt, strictJson);
        }
    }

    private String completeWithModel(String model, String systemPrompt, String userPrompt, boolean strictJson)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", Integer.getInteger("monopoly.deepseek.maxTokens", 512));
        body.addProperty("stream", false);
        if (strictJson) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("DeepSeek HTTP " + response.statusCode() + ": " + abbreviate(response.body(), 500));
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("DeepSeek response has no choices.");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new IOException("DeepSeek response has no message content.");
        }
        if (root.has("usage")) {
            AiBattleLogger.log("DeepSeek",
                    "usage model=" + model + " " + root.get("usage"));
        }
        return message.get("content").getAsString();
    }

    public static String model() {
        return System.getProperty("monopoly.deepseek.model",
                System.getenv().getOrDefault("DEEPSEEK_MODEL", DEFAULT_MODEL));
    }

    public static String fallbackModel() {
        return System.getProperty("monopoly.deepseek.fallbackModel",
                System.getenv().getOrDefault("DEEPSEEK_FALLBACK_MODEL", DEFAULT_FALLBACK_MODEL));
    }

    public static String baseUrl() {
        String raw = System.getProperty("monopoly.deepseek.baseUrl",
                System.getenv().getOrDefault("DEEPSEEK_BASE_URL", DEFAULT_BASE_URL));
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("monopoly.deepseek.enabled", "true"));
    }

    public static boolean preferFallbackForStrictJson() {
        return Boolean.parseBoolean(System.getProperty("monopoly.deepseek.preferFallbackForStrictJson",
                System.getenv().getOrDefault("MONOPOLY_DEEPSEEK_PREFER_FALLBACK_JSON", "false")));
    }

    private static boolean preferredJsonCircuitOpen() {
        return PREFERRED_JSON_FAILURES.get() >= jsonFailureThreshold();
    }

    private static int jsonFailureThreshold() {
        return Integer.getInteger("monopoly.deepseek.jsonFailureThreshold", 2);
    }

    private static String apiKey() throws IOException {
        String key = System.getProperty("monopoly.deepseek.apiKey",
                System.getenv("DEEPSEEK_API_KEY"));
        if (key == null || key.isBlank()) {
            throw new IOException("DeepSeek API key is not configured. Set DEEPSEEK_API_KEY or monopoly.deepseek.apiKey.");
        }
        return key;
    }

    private static int timeoutSeconds() {
        return Integer.getInteger("monopoly.deepseek.timeoutSeconds", 18);
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String abbreviate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}
