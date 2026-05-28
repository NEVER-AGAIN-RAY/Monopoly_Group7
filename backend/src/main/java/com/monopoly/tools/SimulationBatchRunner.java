package com.monopoly.tools;

import com.monopoly.simulation.DecisionBroker;
import com.monopoly.simulation.DecisionTraceSink;
import com.monopoly.simulation.DeepSeekBatchDecisionTeacher;
import com.monopoly.simulation.FirstChoiceDecisionTeacher;
import com.monopoly.simulation.HeuristicDecisionTeacher;
import com.monopoly.simulation.JsonlDecisionTraceSink;
import com.monopoly.simulation.OutcomeDecisionTraceSink;
import com.monopoly.simulation.SimulationDecisionTeacher;
import com.monopoly.simulation.SimulationResult;
import com.monopoly.simulation.SimulationWorker;
import com.monopoly.simulation.StrategicHeuristicDecisionTeacher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Offline batch simulator for collecting decision labels from real game states.
 */
public final class SimulationBatchRunner {

    private SimulationBatchRunner() {
    }

    public static void main(String[] args) throws Exception {
        int games = Integer.getInteger("monopoly.simulation.games", 8);
        int parallel = Integer.getInteger("monopoly.simulation.parallel", 4);
        int players = Integer.getInteger("monopoly.simulation.players", 3);
        List<Integer> playerCounts = playerCounts(players);
        int maxSnapshots = Integer.getInteger("monopoly.simulation.maxSnapshots", 240);
        int batchSize = Integer.getInteger("monopoly.simulation.batchSize", 16);
        long batchWaitMs = Long.getLong("monopoly.simulation.batchWaitMs", 80L);
        long runtimeSeconds = Long.getLong("monopoly.simulation.runtimeSeconds", 60L);

        SimulationDecisionTeacher teacher = teacher();
        DecisionTraceSink sink = traceSink();
        boolean outcomeTrace = isOutcomeTrace();
        try (DecisionBroker broker = new DecisionBroker(
                teacher,
                batchSize,
                Duration.ofMillis(batchWaitMs),
                sink,
                maxByKind())) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
            try {
                List<Future<SimulationResult>> futures = new ArrayList<>();
                String prefix = System.getProperty("monopoly.simulation.sessionPrefix",
                        "sim-" + System.currentTimeMillis());
                for (int i = 1; i <= games; i++) {
                    int gamePlayers = playerCounts.get((i - 1) % playerCounts.size());
                    futures.add(pool.submit(new SimulationWorker(
                            prefix + "-g" + i,
                            gamePlayers,
                            broker,
                            maxSnapshots,
                            Duration.ofSeconds(runtimeSeconds),
                            outcomeTrace ? sink::recordGameResult : null)));
                }
                for (Future<SimulationResult> future : futures) {
                    SimulationResult result = future.get();
                    System.out.println("[SIM] " + result);
                }
            } finally {
                pool.shutdownNow();
            }
            System.out.println("[SIM] decisions=" + broker.getDecisionCount()
                    + " teacherCalls=" + broker.getTeacherCallCount()
                    + " byKind=" + broker.getDecisionCountsByKind());
        }
    }

    private static SimulationDecisionTeacher teacher() {
        String mode = System.getProperty("monopoly.simulation.teacher", "first")
                .trim()
                .toLowerCase(Locale.ROOT);
        if ("deepseek".equals(mode)) {
            return new DeepSeekBatchDecisionTeacher();
        }
        if ("first".equals(mode) || "first_choice".equals(mode)) {
            return new FirstChoiceDecisionTeacher();
        }
        if ("strategic".equals(mode) || "strategic_heuristic".equals(mode)) {
            return new StrategicHeuristicDecisionTeacher();
        }
        return new HeuristicDecisionTeacher();
    }

    private static List<Integer> playerCounts(int fallback) {
        String raw = System.getProperty("monopoly.simulation.playerCounts", "").trim();
        List<Integer> out = new ArrayList<>();
        if (!raw.isBlank()) {
            for (String part : raw.split(",")) {
                try {
                    int n = Integer.parseInt(part.trim());
                    if (n >= 2 && n <= 5) {
                        out.add(n);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (out.isEmpty()) {
            out.add(Math.max(2, Math.min(5, fallback)));
        }
        return List.copyOf(out);
    }

    private static DecisionTraceSink traceSink() throws Exception {
        String path = System.getProperty("monopoly.simulation.tracePath", "").trim();
        if (path.isBlank()) {
            return DecisionTraceSink.NONE;
        }
        Path tracePath = Path.of(path);
        String mode = System.getProperty("monopoly.simulation.traceMode", "fail_if_exists")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (mode.isBlank()) {
            mode = "fail_if_exists";
        }
        switch (mode) {
            case "append" -> {
                return traceSinkForMode(tracePath);
            }
            case "overwrite", "replace", "truncate" -> {
                Files.deleteIfExists(tracePath);
                return traceSinkForMode(tracePath);
            }
            case "fail", "fail_if_exists", "create_new" -> {
                if (Files.exists(tracePath)) {
                    throw new IllegalStateException("decision trace already exists: " + tracePath
                            + " (set -Dmonopoly.simulation.traceMode=append or overwrite explicitly)");
                }
                return traceSinkForMode(tracePath);
            }
            default -> throw new IllegalArgumentException(
                    "unsupported monopoly.simulation.traceMode: " + mode
                            + " (expected fail_if_exists, append, or overwrite)");
        }
    }

    private static DecisionTraceSink traceSinkForMode(Path tracePath) throws Exception {
        return isOutcomeTrace()
                ? new OutcomeDecisionTraceSink(tracePath)
                : new JsonlDecisionTraceSink(tracePath);
    }

    private static boolean isOutcomeTrace() {
        String schema = System.getProperty("monopoly.simulation.traceSchema", "decision")
                .trim()
                .toLowerCase(Locale.ROOT);
        return "outcome".equals(schema) || "outcome_weighted".equals(schema);
    }

    private static Map<String, Long> maxByKind() {
        String raw = System.getProperty("monopoly.simulation.maxByKind", "").trim();
        if (raw.isBlank()) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
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
                    out.put(pieces[0].trim().toUpperCase(Locale.ROOT), limit);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return Map.copyOf(out);
    }
}
