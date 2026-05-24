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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI-compatible chat client used by the LLM strategy.
 */
public final class DeepSeekClient {

    private static final Gson GSON = new Gson();
    private static final String DEFAULT_PROVIDER = "deepseek";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final String DEFAULT_FALLBACK_MODEL = "deepseek-chat";
    private static final String DEFAULT_OPENAI_MODEL = "gpt-5.1";
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
                    providerLabel() + " strict JSON configured for fallback; using fallback model=" + fallbackModel()
                            + " preferred=" + model());
            return completeFallback(systemPrompt, userPrompt, true);
        }
        if (strictJson && preferredJsonCircuitOpen()) {
            AiBattleLogger.log("DeepSeek",
                    providerLabel() + " preferred JSON circuit open; using fallback model=" + fallbackModel());
            return completeFallback(systemPrompt, userPrompt, true);
        }
        int attempts = maxAttempts();
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
                        providerLabel() + " retryable error attempt=" + attempt + "/" + attempts
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
        long base = Long.getLong(providerProperty("retryBaseMs"),
                Long.getLong("monopoly.deepseek.retryBaseMs", 750L));
        long cap = Long.getLong(providerProperty("retryMaxMs"),
                Long.getLong("monopoly.deepseek.retryMaxMs", 8000L));
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
                    providerLabel() + " model fallback " + preferredModel + " -> " + fallback
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
        int attempts = maxAttempts();
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
                        providerLabel() + " retryable fallback error attempt=" + attempt + "/" + attempts
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
                providerLabel() + " preferred JSON failure " + failures + "/" + jsonFailureThreshold()
                        + ": " + reason);
        if (failures == jsonFailureThreshold()) {
            AiBattleLogger.log("DeepSeek",
                    providerLabel() + " preferred JSON circuit opened; future strict JSON calls use fallback model="
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
            throw new IOException(missingKeyMessage());
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        if (isOpenAiProvider()) {
            String temperature = System.getProperty("monopoly.openai.temperature", "").trim();
            if (!temperature.isBlank()) {
                body.addProperty("temperature", Double.parseDouble(temperature));
            }
            body.addProperty("max_completion_tokens", maxTokens());
        } else {
            body.addProperty("temperature", 0.2);
            body.addProperty("max_tokens", maxTokens());
        }
        body.addProperty("stream", false);
        if (isOpenAiProvider()) {
            body.addProperty("store", false);
            String serviceTier = openAiServiceTier();
            if (!serviceTier.isBlank()) {
                body.addProperty("service_tier", serviceTier);
            }
            String reasoningEffort = openAiReasoningEffort();
            if (!reasoningEffort.isBlank()) {
                body.addProperty("reasoning_effort", reasoningEffort);
            }
        }
        if (strictJson && responseFormatEnabled()) {
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
            throw new IOException(providerLabel() + " HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body(), 500));
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
            usage.addProperty("provider", provider());
            LAST_USAGE.set(usage);
            AiBattleLogger.log("DeepSeek", "usage model=" + model + " provider=" + provider() + " "
                    + root.get("usage"));
        }
        return message.get("content").getAsString();
    }

    public static String provider() {
        String raw = System.getProperty("monopoly.llm.provider",
                System.getenv().getOrDefault("MONOPOLY_LLM_PROVIDER", DEFAULT_PROVIDER));
        raw = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (raw) {
            case "openai", "gpt" -> "openai";
            default -> "deepseek";
        };
    }

    public static String providerLabel() {
        return isOpenAiProvider() ? "OpenAI" : "DeepSeek";
    }

    public static String model() {
        if (isOpenAiProvider()) {
            return System.getProperty("monopoly.openai.model",
                    System.getenv().getOrDefault("OPENAI_MODEL", DEFAULT_OPENAI_MODEL));
        }
        return System.getProperty("monopoly.deepseek.model",
                System.getenv().getOrDefault("DEEPSEEK_MODEL", DEFAULT_MODEL));
    }

    public static String fallbackModel() {
        if (isOpenAiProvider()) {
            return System.getProperty("monopoly.openai.fallbackModel",
                    System.getenv().getOrDefault("OPENAI_FALLBACK_MODEL", model()));
        }
        return System.getProperty("monopoly.deepseek.fallbackModel",
                System.getenv().getOrDefault("DEEPSEEK_FALLBACK_MODEL", DEFAULT_FALLBACK_MODEL));
    }

    public static String baseUrl() {
        String raw;
        if (isOpenAiProvider()) {
            raw = System.getProperty("monopoly.openai.baseUrl",
                    System.getenv().getOrDefault("OPENAI_BASE_URL", DEFAULT_OPENAI_BASE_URL));
        } else {
            raw = System.getProperty("monopoly.deepseek.baseUrl",
                    System.getenv().getOrDefault("DEEPSEEK_BASE_URL", DEFAULT_BASE_URL));
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    public static boolean enabled() {
        String providerEnabled = System.getProperty(providerProperty("enabled"));
        if (providerEnabled != null) {
            return Boolean.parseBoolean(providerEnabled);
        }
        String llmEnabled = System.getProperty("monopoly.llm.enabled");
        if (llmEnabled != null) {
            return Boolean.parseBoolean(llmEnabled);
        }
        return Boolean.parseBoolean(System.getProperty("monopoly.deepseek.enabled", "true"));
    }

    public static boolean preferFallbackForStrictJson() {
        String envName = isOpenAiProvider()
                ? "MONOPOLY_OPENAI_PREFER_FALLBACK_JSON"
                : "MONOPOLY_DEEPSEEK_PREFER_FALLBACK_JSON";
        return Boolean.parseBoolean(System.getProperty(providerProperty("preferFallbackForStrictJson"),
                System.getenv().getOrDefault(envName, "false")));
    }

    private static boolean preferredJsonCircuitOpen() {
        return PREFERRED_JSON_FAILURES.get() >= jsonFailureThreshold();
    }

    private static int jsonFailureThreshold() {
        return Integer.getInteger("monopoly.deepseek.jsonFailureThreshold", 2);
    }

    static String configuredApiKeyForTest() throws IOException {
        return apiKey();
    }

    private static String apiKey() throws IOException {
        String propertyKey = System.getProperty(providerProperty("apiKey"));
        if (propertyKey == null && !isOpenAiProvider()) {
            propertyKey = System.getProperty("monopoly.deepseek.apiKey");
        }
        if (propertyKey != null) {
            if (propertyKey.isBlank()) {
                throw new IOException(missingKeyMessage());
            }
            return propertyKey.trim();
        }
        String key = isOpenAiProvider()
                ? firstNonBlank(
                        System.getenv("OPENAI_API_KEY"),
                        System.getenv("MONOPOLY_OPENAI_API_KEY"),
                        apiKeyFromDotEnv())
                : firstNonBlank(
                        System.getenv("DEEPSEEK_API_KEY"),
                        System.getenv("MONOPOLY_DEEPSEEK_API_KEY"),
                        apiKeyFromDotEnv());
        if (key == null || key.isBlank()) {
            throw new IOException(missingKeyMessage());
        }
        return key.trim();
    }

    private static String apiKeyFromDotEnv() throws IOException {
        String rawPath = System.getProperty(providerProperty("envFile"),
                isOpenAiProvider()
                        ? System.getProperty("monopoly.llm.envFile", ".env.openai")
                        : System.getProperty("monopoly.llm.envFile", ".env"));
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        Path path = Paths.get(rawPath);
        if (!path.isAbsolute()) {
            path = Paths.get("").toAbsolutePath().resolve(path).normalize();
        }
        if (!Files.isRegularFile(path)) {
            return "";
        }
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("export ")) {
                trimmed = trimmed.substring("export ".length()).trim();
            }
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                idx = trimmed.indexOf(':');
            }
            if (idx <= 0) {
                continue;
            }
            String name = trimmed.substring(0, idx).trim();
            if (!isSupportedKeyName(name)) {
                continue;
            }
            String value = unquote(trimmed.substring(idx + 1).trim());
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int timeoutSeconds() {
        return Integer.getInteger(providerProperty("timeoutSeconds"),
                Integer.getInteger("monopoly.deepseek.timeoutSeconds", 18));
    }

    private static int maxAttempts() {
        return Math.max(1, Integer.getInteger(providerProperty("maxAttempts"),
                Integer.getInteger("monopoly.deepseek.maxAttempts", 3)));
    }

    private static int maxTokens() {
        return Integer.getInteger(providerProperty("maxTokens"),
                Integer.getInteger("monopoly.deepseek.maxTokens", 512));
    }

    private static boolean isOpenAiProvider() {
        return "openai".equals(provider());
    }

    private static String providerProperty(String name) {
        return "monopoly." + provider() + "." + name;
    }

    private static String openAiServiceTier() {
        String raw = System.getProperty("monopoly.openai.serviceTier",
                System.getenv().getOrDefault("OPENAI_SERVICE_TIER", ""));
        raw = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("fast".equals(raw)) {
            return "auto";
        }
        return raw;
    }

    private static String openAiReasoningEffort() {
        String raw = System.getProperty("monopoly.openai.reasoningEffort",
                System.getenv().getOrDefault("OPENAI_REASONING_EFFORT", ""));
        raw = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("xhigh".equals(raw) && !model().toLowerCase(java.util.Locale.ROOT).contains("codex")) {
            return "high";
        }
        return raw;
    }

    private static boolean isSupportedKeyName(String name) {
        String normalized = name == null ? "" : name.trim()
                .toUpperCase(java.util.Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        if (isOpenAiProvider()) {
            return "OPENAIAPIKEY".equals(normalized)
                    || "MONOPOLYOPENAIAPIKEY".equals(normalized)
                    || "APIKEY".equals(normalized);
        }
        return "DEEPSEEKAPIKEY".equals(normalized)
                || "MONOPOLYDEEPSEEKAPIKEY".equals(normalized)
                || "APIKEY".equals(normalized);
    }

    private static boolean responseFormatEnabled() {
        return Boolean.parseBoolean(System.getProperty(providerProperty("responseFormatEnabled"),
                System.getProperty("monopoly.llm.responseFormatEnabled", "true")));
    }

    private static String missingKeyMessage() {
        if (isOpenAiProvider()) {
            return "OpenAI API key is not configured. Set OPENAI_API_KEY, MONOPOLY_OPENAI_API_KEY, .env.openai, or -Dmonopoly.openai.apiKey.";
        }
        return "DeepSeek API key is not configured. Set DEEPSEEK_API_KEY, MONOPOLY_DEEPSEEK_API_KEY, .env, or -Dmonopoly.deepseek.apiKey.";
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
