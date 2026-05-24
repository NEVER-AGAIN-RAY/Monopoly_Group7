package com.monopoly.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.pattern.strategy.DeepSeekClient;
import com.monopoly.simulation.DeepSeekBatchDecisionTeacher;
import com.monopoly.simulation.FirstChoiceDecisionTeacher;
import com.monopoly.simulation.HeuristicDecisionTeacher;
import com.monopoly.simulation.JsonlDecisionTraceSink;
import com.monopoly.simulation.SimulationDecisionCandidate;
import com.monopoly.simulation.SimulationDecisionRequest;
import com.monopoly.simulation.SimulationDecisionResult;
import com.monopoly.simulation.SimulationDecisionTeacher;
import com.monopoly.simulation.StrategicHeuristicDecisionTeacher;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Re-labels an existing decision trace with a new teacher while preserving the
 * backend-generated legal candidate envelope.
 */
public final class TraceRelabeler {

    private static final Gson GSON = new Gson();

    private TraceRelabeler() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromProperties();
        Summary summary = run(config);
        System.out.println(GSON.toJson(summary.toJson()));
    }

    static Summary run(Config config) throws Exception {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (config.inputPath().toAbsolutePath().normalize().equals(config.outputPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("inputPath and outputPath must be different");
        }
        prepareOutput(config.outputPath(), config.traceMode());
        SimulationDecisionTeacher teacher = teacher(config.teacherMode());
        Summary summary = new Summary(
                config.inputPath().toString(),
                config.outputPath().toString(),
                config.teacherMode());
        try (BufferedReader reader = Files.newBufferedReader(config.inputPath(), StandardCharsets.UTF_8);
             JsonlDecisionTraceSink sink = new JsonlDecisionTraceSink(config.outputPath())) {
            List<SimulationDecisionRequest> batch = new ArrayList<>();
            String line;
            long lineNumber = 0L;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                summary.rowsRead++;
                JsonObject row = parseRow(line, lineNumber);
                String source = inputSource(row);
                if (!config.includeSources().isEmpty() && !config.includeSources().contains(source)) {
                    summary.rowsSkipped++;
                    continue;
                }
                SimulationDecisionRequest request = requestFromRow(row, lineNumber);
                if (!summary.reserveKindQuota(request.getDecisionKind(), config.maxRowsByKind())) {
                    summary.rowsSkipped++;
                    continue;
                }
                batch.add(request);
                if (batch.size() >= config.batchSize()) {
                    flushBatch(batch, teacher, sink, config.requiredResultSource(), summary);
                    batch.clear();
                    if (config.maxRows() > 0L && summary.rowsRelabeled >= config.maxRows()) {
                        break;
                    }
                }
                if (config.maxRows() > 0L && summary.rowsRelabeled + batch.size() >= config.maxRows()) {
                    int keep = (int) Math.max(0L, config.maxRows() - summary.rowsRelabeled);
                    if (batch.size() > keep) {
                        batch.subList(keep, batch.size()).clear();
                    }
                    flushBatch(batch, teacher, sink, config.requiredResultSource(), summary);
                    batch.clear();
                    break;
                }
            }
            flushBatch(batch, teacher, sink, config.requiredResultSource(), summary);
        }
        return summary;
    }

    private static void flushBatch(
            List<SimulationDecisionRequest> requests,
            SimulationDecisionTeacher teacher,
            JsonlDecisionTraceSink sink,
            String requiredResultSource,
            Summary summary) throws Exception {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        List<SimulationDecisionResult> results = teacher.decideBatch(List.copyOf(requests));
        summary.teacherCalls++;
        Map<String, SimulationDecisionResult> byDecision = new HashMap<>();
        if (results != null) {
            for (SimulationDecisionResult result : results) {
                if (result != null) {
                    byDecision.put(result.getDecisionId(), result);
                }
            }
        }
        List<SimulationDecisionResult> orderedResults = new ArrayList<>();
        for (SimulationDecisionRequest request : requests) {
            SimulationDecisionResult result = byDecision.get(request.getDecisionId());
            if (result == null) {
                throw new IllegalStateException("teacher returned no result for " + request.getDecisionId());
            }
            if (!request.hasCandidate(result.getChoiceId())) {
                throw new IllegalStateException("teacher chose invalid candidate " + result.getChoiceId()
                        + " for " + request.getDecisionId());
            }
            String resultSource = resultSource(result);
            if (!requiredResultSource.isBlank() && !requiredResultSource.equals(resultSource)) {
                throw new IllegalStateException("teacher result source " + resultSource
                        + " does not match required source " + requiredResultSource
                        + " for " + request.getDecisionId());
            }
            orderedResults.add(result);
        }
        for (int i = 0; i < requests.size(); i++) {
            SimulationDecisionRequest request = requests.get(i);
            SimulationDecisionResult result = orderedResults.get(i);
            sink.record(request, result);
            summary.rowsRelabeled++;
            summary.sources.merge(resultSource(result), 1L, Long::sum);
            summary.kinds.merge(request.getDecisionKind(), 1L, Long::sum);
        }
    }

    private static SimulationDecisionTeacher teacher(String mode) {
        return switch (mode) {
            case "deepseek" -> {
                configureDeepSeekKeyFromEnvironment();
                requireDeepSeekReady();
                yield new DeepSeekBatchDecisionTeacher();
            }
            case "heuristic", "local", "local_heuristic" -> new HeuristicDecisionTeacher();
            case "strategic", "strategic_heuristic" -> new StrategicHeuristicDecisionTeacher();
            case "first", "first_choice" -> new FirstChoiceDecisionTeacher();
            default -> throw new IllegalArgumentException(
                    "unsupported monopoly.relabel.teacher: " + mode
                            + " (expected deepseek, heuristic, or first)");
        };
    }

    private static void configureDeepSeekKeyFromEnvironment() {
        String property = System.getProperty("monopoly.deepseek.apiKey", "").trim();
        if (!property.isBlank()) {
            return;
        }
        String alias = System.getenv().getOrDefault("MONOPOLY_DEEPSEEK_API_KEY", "").trim();
        if (!alias.isBlank()) {
            System.setProperty("monopoly.deepseek.apiKey", alias);
        }
    }

    private static void requireDeepSeekReady() {
        if (!DeepSeekClient.enabled()) {
            throw new IllegalStateException("monopoly.deepseek.enabled=false; refusing DeepSeek relabel");
        }
        String key = System.getProperty("monopoly.deepseek.apiKey",
                System.getenv().getOrDefault("DEEPSEEK_API_KEY", "")).trim();
        if (key.isBlank()) {
            throw new IllegalStateException(
                    "DeepSeek API key is not configured. Set DEEPSEEK_API_KEY or MONOPOLY_DEEPSEEK_API_KEY.");
        }
    }

    private static JsonObject parseRow(String line, long lineNumber) {
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("line is not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSONL row at line " + lineNumber + ": " + e.getMessage(), e);
        }
    }

    private static SimulationDecisionRequest requestFromRow(JsonObject row, long lineNumber) {
        if (!row.has("request") || !row.get("request").isJsonObject()) {
            throw new IllegalArgumentException("missing request object at line " + lineNumber);
        }
        JsonObject request = row.getAsJsonObject("request");
        JsonArray candidatesJson = objectArray(request, "candidates", lineNumber);
        List<SimulationDecisionCandidate> candidates = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : candidatesJson) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("candidate is not an object at line " + lineNumber);
            }
            JsonObject candidate = element.getAsJsonObject();
            String id = string(candidate, "id", lineNumber, true);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate candidate id " + id + " at line " + lineNumber);
            }
            JsonObject payload = candidate.has("payload") && candidate.get("payload").isJsonObject()
                    ? candidate.getAsJsonObject("payload")
                    : new JsonObject();
            candidates.add(new SimulationDecisionCandidate(
                    id,
                    string(candidate, "summary", lineNumber, false),
                    payload));
        }
        return new SimulationDecisionRequest(
                string(request, "decisionId", lineNumber, true),
                string(request, "sessionId", lineNumber, false),
                string(request, "actorPlayerId", lineNumber, false),
                string(request, "decisionKind", lineNumber, true),
                longValue(request, "stateSequence", 0L),
                request.has("context") && request.get("context").isJsonObject()
                        ? request.getAsJsonObject("context")
                        : new JsonObject(),
                candidates);
    }

    private static JsonArray objectArray(JsonObject object, String key, long lineNumber) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("missing array " + key + " at line " + lineNumber);
        }
        return object.getAsJsonArray(key);
    }

    private static String inputSource(JsonObject row) {
        if (!row.has("result") || !row.get("result").isJsonObject()) {
            return "";
        }
        JsonObject result = row.getAsJsonObject("result");
        if (!result.has("metadata") || !result.get("metadata").isJsonObject()) {
            return "";
        }
        JsonObject metadata = result.getAsJsonObject("metadata");
        if (!metadata.has("source") || metadata.get("source").isJsonNull()) {
            return "";
        }
        return metadata.get("source").getAsString();
    }

    private static String resultSource(SimulationDecisionResult result) {
        JsonObject metadata = result.getMetadata();
        if (!metadata.has("source") || metadata.get("source").isJsonNull()) {
            return "";
        }
        return metadata.get("source").getAsString();
    }

    private static String string(JsonObject object, String key, long lineNumber, boolean required) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            if (required) {
                throw new IllegalArgumentException("missing " + key + " at line " + lineNumber);
            }
            return "";
        }
        return object.get(key).getAsString();
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static void prepareOutput(Path outputPath, String mode) throws IOException {
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "append" -> {
                return;
            }
            case "overwrite", "replace", "truncate" -> Files.deleteIfExists(outputPath);
            case "fail", "fail_if_exists", "create_new" -> {
                if (Files.exists(outputPath)) {
                    throw new IllegalStateException("decision trace already exists: " + outputPath
                            + " (set monopoly.relabel.traceMode=append or overwrite explicitly)");
                }
            }
            default -> throw new IllegalArgumentException(
                    "unsupported monopoly.relabel.traceMode: " + mode
                            + " (expected fail_if_exists, append, or overwrite)");
        }
    }

    record Config(
            Path inputPath,
            Path outputPath,
            String teacherMode,
            int batchSize,
            long maxRows,
            String traceMode,
            Set<String> includeSources,
            Map<String, Long> maxRowsByKind,
            String requiredResultSource) {

        static Config fromProperties() {
            Path input = requiredPath("monopoly.relabel.inputPath");
            Path output = requiredPath("monopoly.relabel.outputPath");
            String teacher = System.getProperty("monopoly.relabel.teacher", "deepseek")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            String requiredSource = System.getProperty(
                    "monopoly.relabel.requiredResultSource",
                    "deepseek".equals(teacher) ? "deepseek" : "").trim();
            return new Config(
                    input,
                    output,
                    teacher,
                    Math.max(1, Integer.getInteger("monopoly.relabel.batchSize", 32)),
                    Math.max(0L, Long.getLong("monopoly.relabel.maxRows", 0L)),
                    System.getProperty("monopoly.relabel.traceMode", "fail_if_exists"),
                    csvSet(System.getProperty("monopoly.relabel.includeSources", "")),
                    quotas(System.getProperty("monopoly.relabel.maxByKind", "")),
                    requiredSource);
        }

        private static Path requiredPath(String key) {
            String raw = System.getProperty(key, "").trim();
            if (raw.isBlank()) {
                throw new IllegalArgumentException("missing required property " + key);
            }
            return Path.of(raw);
        }

        private static Set<String> csvSet(String raw) {
            Set<String> out = new LinkedHashSet<>();
            if (raw == null || raw.isBlank()) {
                return Set.of();
            }
            for (String part : raw.split(",")) {
                String value = part.trim();
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
            return Set.copyOf(out);
        }

        private static Map<String, Long> quotas(String raw) {
            if (raw == null || raw.isBlank()) {
                return Map.of();
            }
            Map<String, Long> out = new HashMap<>();
            for (String part : raw.split(",")) {
                String text = part.trim();
                if (text.isBlank()) {
                    continue;
                }
                String[] pieces = text.split("[:=]", 2);
                if (pieces.length != 2) {
                    continue;
                }
                try {
                    long limit = Long.parseLong(pieces[1].trim());
                    if (limit > 0L) {
                        out.put(normalizeKind(pieces[0]), limit);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return Map.copyOf(out);
        }
    }

    static final class Summary {
        private final String inputPath;
        private final String outputPath;
        private final String teacher;
        private long rowsRead;
        private long rowsSkipped;
        private long rowsRelabeled;
        private long teacherCalls;
        private final Map<String, Long> sources = new HashMap<>();
        private final Map<String, Long> kinds = new HashMap<>();
        private final Map<String, Long> reservedKinds = new HashMap<>();

        Summary(String inputPath, String outputPath, String teacher) {
            this.inputPath = inputPath;
            this.outputPath = outputPath;
            this.teacher = teacher;
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("inputPath", inputPath);
            root.addProperty("outputPath", outputPath);
            root.addProperty("teacher", teacher);
            root.addProperty("rowsRead", rowsRead);
            root.addProperty("rowsSkipped", rowsSkipped);
            root.addProperty("rowsRelabeled", rowsRelabeled);
            root.addProperty("teacherCalls", teacherCalls);
            root.add("byResultSource", mapJson(sources));
            root.add("byDecisionKind", mapJson(kinds));
            return root;
        }

        boolean reserveKindQuota(String kind, Map<String, Long> quotas) {
            if (quotas == null || quotas.isEmpty()) {
                return true;
            }
            String normalized = normalizeKind(kind);
            Long limit = quotas.get(normalized);
            if (limit == null || limit <= 0L) {
                return true;
            }
            long current = reservedKinds.getOrDefault(normalized, 0L);
            if (current >= limit) {
                return false;
            }
            reservedKinds.put(normalized, current + 1L);
            return true;
        }

        private static JsonObject mapJson(Map<String, Long> values) {
            JsonObject out = new JsonObject();
            new TreeMap<>(values).forEach(out::addProperty);
            return out;
        }
    }

    private static String normalizeKind(String kind) {
        return kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
    }
}
