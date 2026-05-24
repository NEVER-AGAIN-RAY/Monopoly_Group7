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
    private static final ThreadLocal<JsonObject> LAST_USAGE = new ThreadLocal<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
            .build();

    public String complete(String systemPrompt, String userPrompt, boolean strictJson)
            throws IOException, InterruptedException {
        LAST_USAGE.remove();
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
        int attempts = Math.max(1, Integer.getInteger("monopoly.deepseek.maxAttempts", 3));
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return completePreferred(systemPrompt, userPrompt, strictJson, model());
            } catch (IOException ex) {
                last = ex;
                if (strictJson) {
                    recordPreferredJsonFailure(ex.getMessage());
                }
                if (attempt >= attempts || !isRetryable(ex)) {
                    throw ex;
                }
                long sleepMs = retrySleepMs(attempt);
                AiBattleLogger.log("DeepSeek",
                        "retryable error attempt=" + attempt + "/" + attempts
                                + " sleepMs=" + sleepMs
                                + " reason=" + ex.getMessage());
                sleep(sleepMs);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("DeepSeek request failed without response");
    }

    public JsonObject consumeLastUsage() {
        JsonObject usage = LAST_USAGE.get();
        LAST_USAGE.remove();
        return usage == null ? new JsonObject() : usage.deepCopy();
    }

    public static boolean isRetryable(IOException ex) {
        String message = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        return message.contains("HTTP 408")
                || message.contains("HTTP 409")
                || message.contains("HTTP 425")
                || message.contains("HTTP 429")
                || message.contains("HTTP 500")
                || message.contains("HTTP 502")
                || message.contains("HTTP 503")
                || message.contains("HTTP 504");
    }

    private static long retrySleepMs(int attempt) {
        long base = Long.getLong("monopoly.deepseek.retryBaseMs", 750L);
        long cap = Long.getLong("monopoly.deepseek.retryMaxMs", 8000L);
        long value = base;
        for (int i = 1; i < Math.max(1, attempt); i++) {
            value = Math.min(cap, value * 2L);
        }
        return Math.min(cap, value);
    }

    private static void sleep(long millis) throws InterruptedException {
        if (millis > 0L) {
            Thread.sleep(millis);
        }
    }

    private String completePreferred(
            String systemPrompt,
            String userPrompt,
            boolean strictJson,
            String preferredModel) throws IOException, InterruptedException {
        try {
            return completeWithModel(preferredModel, systemPrompt, userPrompt, strictJson);
        } catch (IOException ex) {
            String fallback = fallbackModel();
            if (fallback.equals(preferredModel) || !isJsonCompatibilityError(ex)) {
                throw ex;
            }
            AiBattleLogger.log("DeepSeek",
                    "model fallback " + preferredModel + " -> " + fallback
                            + " after " + ex.getMessage());
            return completeWithModel(fallback, systemPrompt, userPrompt, strictJson);
        }
    }

    private static boolean isJsonCompatibilityError(IOException ex) {
        String message = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        return message.contains("response_format")
                || message.contains("json_object")
                || message.contains("JSON")
                || message.contains("400")
                || message.contains("422");
    }

    public String completeFallback(String systemPrompt, String userPrompt, boolean strictJson)
            throws IOException, InterruptedException {
        LAST_USAGE.remove();
        int attempts = Math.max(1, Integer.getInteger("monopoly.deepseek.maxAttempts", 3));
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return completeWithModel(fallbackModel(), systemPrompt, userPrompt, strictJson);
            } catch (IOException ex) {
                last = ex;
                if (attempt >= attempts || !isRetryable(ex)) {
                    throw ex;
                }
                long sleepMs = retrySleepMs(attempt);
                AiBattleLogger.log("DeepSeek",
                        "retryable fallback error attempt=" + attempt + "/" + attempts
                                + " sleepMs=" + sleepMs
                                + " reason=" + ex.getMessage());
                sleep(sleepMs);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("DeepSeek fallback request failed without response");
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

    private String completeWithModel(String model, String systemPrompt, String userPrompt, boolean strictJson)
            throws IOException, InterruptedException {
        String key = apiKey();
        if (key.isBlank()) {
            throw new IOException("DeepSeek API key is not configured. Set DEEPSEEK_API_KEY or -Dmonopoly.deepseek.apiKey.");
        }
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
                .header("Authorization", "Bearer " + key)
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
            JsonObject usage = root.getAsJsonObject("usage").deepCopy();
            usage.addProperty("model", model);
            LAST_USAGE.set(usage);
            AiBattleLogger.log("DeepSeek", "usage model=" + model + " " + root.get("usage"));
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
                System.getenv().getOrDefault("DEEPSEEK_API_KEY", ""));
        if (key == null || key.isBlank()) {
            throw new IOException("DeepSeek API key is not configured. Set DEEPSEEK_API_KEY or -Dmonopoly.deepseek.apiKey.");
        }
        return key.trim();
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
