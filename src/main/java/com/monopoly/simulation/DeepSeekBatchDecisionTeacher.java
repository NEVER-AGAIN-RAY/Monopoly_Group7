package com.monopoly.simulation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.pattern.strategy.DeepSeekClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One DeepSeek request labels many independent game decisions.
 */
public final class DeepSeekBatchDecisionTeacher implements SimulationDecisionTeacher {

    private static final String SYSTEM_PROMPT = """
            You are a strong Monopoly Deal teacher for offline imitation learning.
            For each independent case, choose exactly one legal candidate id.
            Return only compact JSON:
            {"decisions":[{"decisionId":"id","choiceId":"c1"}]}.
            Do not explain, do not add markdown, and do not invent ids.
            """;

    private final DeepSeekClient client = new DeepSeekClient();
    private final SimulationDecisionTeacher fallback = new HeuristicDecisionTeacher();

    @Override
    public List<SimulationDecisionResult> decideBatch(List<SimulationDecisionRequest> requests)
            throws Exception {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (!DeepSeekClient.enabled()) {
            return withSource(fallback.decideBatch(requests), "local_fallback");
        }
        try {
            String prompt = buildPrompt(requests);
            String raw = client.complete(SYSTEM_PROMPT, prompt, true);
            return completeMissingOrInvalid(requests, parse(raw), raw, client.consumeLastUsage());
        } catch (Exception e) {
            return fallbackForBatch(requests, "local_fallback_error", e.getClass().getSimpleName()
                    + ": " + String.valueOf(e.getMessage()));
        }
    }

    private static String buildPrompt(List<SimulationDecisionRequest> requests) {
        JsonObject root = new JsonObject();
        root.addProperty("promptVersion", "monopoly-deal-batch-teacher-v1");
        root.addProperty("caseCount", requests.size());
        root.addProperty("rule", "Win at 3 complete property sets. Choose only among legal candidate ids.");
        String strategy = sharedStrategy(requests);
        if (strategy != null && !strategy.isBlank()) {
            root.addProperty("strategy", strategy);
        }
        JsonArray cases = new JsonArray();
        for (SimulationDecisionRequest request : requests) {
            JsonObject c = new JsonObject();
            c.addProperty("decisionId", request.getDecisionId());
            c.addProperty("sessionId", request.getSessionId());
            c.addProperty("actorPlayerId", request.getActorPlayerId());
            c.addProperty("decisionKind", request.getDecisionKind());
            c.addProperty("stateSequence", request.getStateSequence());
            c.add("context", compactContext(request.getContextJson()));
            JsonArray candidates = new JsonArray();
            for (SimulationDecisionCandidate candidate : request.getCandidates()) {
                JsonObject row = new JsonObject();
                row.addProperty("id", candidate.getId());
                row.addProperty("summary", candidate.getSummary());
                candidates.add(row);
            }
            c.add("legalCandidates", candidates);
            cases.add(c);
        }
        root.add("cases", cases);
        return root.toString();
    }

    private static String sharedStrategy(List<SimulationDecisionRequest> requests) {
        for (SimulationDecisionRequest request : requests) {
            JsonObject context = request.getContextJson();
            if (context.has("strategy") && !context.get("strategy").isJsonNull()) {
                return context.get("strategy").getAsString();
            }
        }
        return null;
    }

    private static JsonObject compactContext(JsonObject context) {
        JsonObject out = new JsonObject();
        if (context == null) {
            return out;
        }
        copyIfPresent(context, out, "gameMeta");
        copyIfPresent(context, out, "self");
        copyIfPresent(context, out, "players");
        copyIfPresent(context, out, "effectStack");
        if (context.has("decision") && context.get("decision").isJsonObject()) {
            JsonObject decision = context.getAsJsonObject("decision").deepCopy();
            decision.remove("legalCandidates");
            out.add("decision", decision);
        }
        return out;
    }

    private static void copyIfPresent(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) {
            to.add(key, from.get(key).deepCopy());
        }
    }

    private static List<SimulationDecisionResult> parse(String raw) {
        JsonObject root = parseObject(raw);
        JsonArray decisions = root.has("decisions")
                ? root.getAsJsonArray("decisions")
                : new JsonArray();
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (int i = 0; i < decisions.size(); i++) {
            JsonObject row = decisions.get(i).getAsJsonObject();
            if (!row.has("decisionId") || !row.has("choiceId")) {
                continue;
            }
            out.add(new SimulationDecisionResult(
                    row.get("decisionId").getAsString(),
                    row.get("choiceId").getAsString(),
                    raw,
                    null));
        }
        return out;
    }

    private static List<SimulationDecisionResult> completeMissingOrInvalid(
            List<SimulationDecisionRequest> requests,
            List<SimulationDecisionResult> parsed,
            String raw,
            JsonObject usage) throws Exception {
        Map<String, SimulationDecisionResult> parsedById = new HashMap<>();
        if (parsed != null) {
            for (SimulationDecisionResult result : parsed) {
                parsedById.put(result.getDecisionId(), result);
            }
        }
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (SimulationDecisionRequest request : requests) {
            SimulationDecisionResult result = parsedById.get(request.getDecisionId());
            if (result != null && request.hasCandidate(result.getChoiceId())) {
                JsonObject metadata = sourceMetadata("deepseek");
                addUsage(metadata, usage, requests.size());
                out.add(new SimulationDecisionResult(
                        result.getDecisionId(),
                        result.getChoiceId(),
                        raw,
                        metadata));
                continue;
            }
            SimulationDecisionResult fallbackResult = fallbackForOne(request);
            JsonObject metadata = sourceMetadata(result == null ? "local_fallback_missing" : "local_fallback_invalid");
            addUsage(metadata, usage, requests.size());
            out.add(new SimulationDecisionResult(
                    fallbackResult.getDecisionId(),
                    fallbackResult.getChoiceId(),
                    raw,
                    metadata));
        }
        return out;
    }

    private static SimulationDecisionResult fallbackForOne(SimulationDecisionRequest request) throws Exception {
        return new HeuristicDecisionTeacher().decideBatch(List.of(request)).get(0);
    }

    private static List<SimulationDecisionResult> withSource(
            List<SimulationDecisionResult> results,
            String source) {
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (SimulationDecisionResult result : results) {
            out.add(new SimulationDecisionResult(
                    result.getDecisionId(),
                    result.getChoiceId(),
                    result.getRawResponse(),
                    sourceMetadata(source)));
        }
        return out;
    }

    private static List<SimulationDecisionResult> fallbackForBatch(
            List<SimulationDecisionRequest> requests,
            String source,
            String error) throws Exception {
        List<SimulationDecisionResult> fallbackResults = new HeuristicDecisionTeacher().decideBatch(requests);
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (SimulationDecisionResult result : fallbackResults) {
            JsonObject metadata = sourceMetadata(source);
            metadata.addProperty("error", error);
            out.add(new SimulationDecisionResult(
                    result.getDecisionId(),
                    result.getChoiceId(),
                    result.getRawResponse(),
                    metadata));
        }
        return out;
    }

    private static JsonObject sourceMetadata(String source) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", source);
        if ("deepseek".equals(source)) {
            metadata.addProperty("model", DeepSeekClient.model());
        }
        return metadata;
    }

    private static void addUsage(JsonObject metadata, JsonObject usage, int batchCases) {
        if (metadata == null || usage == null || usage.size() == 0) {
            return;
        }
        metadata.add("usage", usage.deepCopy());
        metadata.addProperty("batchCases", Math.max(1, batchCases));
        metadata.addProperty("promptTokensShare", perCase(usage, "prompt_tokens", batchCases));
        metadata.addProperty("completionTokensShare", perCase(usage, "completion_tokens", batchCases));
        metadata.addProperty("totalTokensShare", perCase(usage, "total_tokens", batchCases));
    }

    private static double perCase(JsonObject usage, String key, int batchCases) {
        if (!usage.has(key) || usage.get(key).isJsonNull()) {
            return 0d;
        }
        try {
            return usage.get(key).getAsDouble() / Math.max(1, batchCases);
        } catch (RuntimeException ignored) {
            return 0d;
        }
    }

    private static JsonObject parseObject(String raw) {
        String s = raw == null ? "" : raw.trim();
        try {
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (RuntimeException first) {
            String extracted = extractFirstJsonObject(s);
            if (extracted == null) {
                throw first;
            }
            return JsonParser.parseString(extracted).getAsJsonObject();
        }
    }

    private static String extractFirstJsonObject(String s) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
                continue;
            }
            if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
