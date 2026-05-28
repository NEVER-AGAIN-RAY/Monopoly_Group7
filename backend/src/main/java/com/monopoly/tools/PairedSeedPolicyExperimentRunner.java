package com.monopoly.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PropertyColorProgress;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiBattleLogger;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.strategy.BrokeredAiPlayStrategy;
import com.monopoly.pattern.strategy.DeepSeekClient;
import com.monopoly.pattern.strategy.DeepSeekAiPlayStrategy;
import com.monopoly.pattern.strategy.LocalRankerAiPlayStrategy;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;
import com.monopoly.simulation.DecisionTraceSink;
import com.monopoly.simulation.DecisionBroker;
import com.monopoly.simulation.JsonlDecisionTraceSink;
import com.monopoly.simulation.OutcomeDecisionTraceSink;
import com.monopoly.simulation.StrategicHeuristicDecisionTeacher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * Runs paired, same-seed policy comparisons such as hard,hard vs hard,llm.
 */
public final class PairedSeedPolicyExperimentRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();
    private static final String DECK_SEED_PROPERTY = "monopoly.deck.seed";
    private static final String FIRST_PLAYER_SEED_PROPERTY = "monopoly.firstPlayer.seed";
    private static final String AI_SEED_PROPERTY = "monopoly.ai.seed";
    private static final long DEFAULT_SEED_BASE = 2026052401L;
    private static final long FIRST_PLAYER_SALT = 0x632BE59BD9B4E019L;
    private static final long AI_SEED_SALT = 0x9E3779B97F4A7C15L;

    private PairedSeedPolicyExperimentRunner() {
    }

    public static void main(String[] args) throws IOException {
        int pairs = Math.max(1, Integer.getInteger("monopoly.pairedBattle.games", 3));
        int maxSnapshotsPerGame = Math.max(20, Integer.getInteger(
                "monopoly.pairedBattle.maxSnapshotsPerGame", 260));
        long timeoutSeconds = Math.max(10L, Long.getLong("monopoly.pairedBattle.timeoutSeconds", 180L));
        long seedBase = Long.getLong("monopoly.pairedBattle.seedBase", DEFAULT_SEED_BASE);
        String controlLineup = System.getProperty("monopoly.pairedBattle.controlLineup", "hard,hard");
        String treatmentLineup = System.getProperty("monopoly.pairedBattle.treatmentLineup", "hard,llm");
        int focusSeat = Math.max(1, Integer.getInteger("monopoly.pairedBattle.focusSeat", 2));
        boolean randomizeFirstPlayer = Boolean.parseBoolean(
                System.getProperty("monopoly.pairedBattle.randomizeFirstPlayer", "false"));
        String sessionPrefix = System.getProperty("monopoly.pairedBattle.sessionId",
                "paired-seed-battle-" + System.currentTimeMillis());
        Path output = Path.of(System.getProperty("monopoly.pairedBattle.output",
                "training/data/models/evaluation/paired-seed-policy-" + timestamp() + ".json"));
        boolean quiet = Boolean.parseBoolean(System.getProperty("monopoly.pairedBattle.quiet", "false"));
        int progressEvery = Math.max(0, Integer.getInteger("monopoly.pairedBattle.progressEvery", 0));
        String llmStrategy = strategyProperty("monopoly.pairedBattle.llmStrategy", "remote");
        String controlLlmStrategy = strategyProperty(
                "monopoly.pairedBattle.controlLlmStrategy", llmStrategy);
        String treatmentLlmStrategy = strategyProperty(
                "monopoly.pairedBattle.treatmentLlmStrategy", llmStrategy);
        String localRankerModelPath = System.getProperty("monopoly.localRanker.modelPath", "").trim();
        String controlLocalRankerModelPath = modelPathProperty(
                "monopoly.pairedBattle.controlLocalRankerModelPath", localRankerModelPath);
        String treatmentLocalRankerModelPath = modelPathProperty(
                "monopoly.pairedBattle.treatmentLocalRankerModelPath", localRankerModelPath);
        Path tracePath = tracePath();
        if (tracePath != null && isTraceOverwrite()) {
            Files.deleteIfExists(tracePath);
        }

        Metrics metrics = new Metrics();
        AiBattleLogger.setMetricsSink(metrics);
        Map<String, String> previousProperties = snapshotProperties();
        String previousBattleLogEnabled = System.getProperty("monopoly.aiBattle.log.enabled");
        PrintStream originalOut = System.out;
        JsonArray pairReports = new JsonArray();
        try {
            if (quiet) {
                if (previousBattleLogEnabled == null) {
                    System.setProperty("monopoly.aiBattle.log.enabled", "false");
                }
                System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            }
            for (int game = 1; game <= pairs; game++) {
                long deckSeed = seedBase + game - 1L;
                long firstPlayerSeed = deckSeed ^ FIRST_PLAYER_SALT;
                long aiSeed = deckSeed ^ AI_SEED_SALT;
                configureSeedProperties(deckSeed, firstPlayerSeed, aiSeed, randomizeFirstPlayer);

                RunSummary control = runGame(
                        sessionPrefix + "-g" + game + "-control",
                        game,
                        "control",
                        controlLineup,
                        focusSeat,
                        randomizeFirstPlayer,
                        maxSnapshotsPerGame,
                        timeoutSeconds,
                        controlLlmStrategy,
                        controlLocalRankerModelPath,
                        null);
                configureSeedProperties(deckSeed, firstPlayerSeed, aiSeed, randomizeFirstPlayer);
                RunSummary treatment = runGame(
                        sessionPrefix + "-g" + game + "-treatment",
                        game,
                        "treatment",
                        treatmentLineup,
                        focusSeat,
                        randomizeFirstPlayer,
                        maxSnapshotsPerGame,
                        timeoutSeconds,
                        treatmentLlmStrategy,
                        treatmentLocalRankerModelPath,
                        tracePath);

                PairComparison comparison = compare(control.focusPlayer(), treatment.focusPlayer());
                pairReports.add(pairReport(
                        game,
                        deckSeed,
                        randomizeFirstPlayer,
                        firstPlayerSeed,
                        aiSeed,
                        control,
                        treatment,
                        comparison));
                printProgressIfNeeded(
                        quiet,
                        originalOut,
                        progressEvery,
                        game,
                        pairs,
                        pairReports);
            }
        } finally {
            if (quiet) {
                System.setOut(originalOut);
                if (previousBattleLogEnabled == null) {
                    System.clearProperty("monopoly.aiBattle.log.enabled");
                } else {
                    System.setProperty("monopoly.aiBattle.log.enabled", previousBattleLogEnabled);
                }
            }
            restoreProperties(previousProperties);
            AiBattleLogger.setMetricsSink(null);
        }

        JsonObject report = new JsonObject();
        report.addProperty("timestamp", Instant.now().toString());
        report.addProperty("controlLineup", controlLineup);
        report.addProperty("treatmentLineup", treatmentLineup);
        report.addProperty("focusSeat", focusSeat);
        report.addProperty("gamesRequested", pairs);
        report.addProperty("seedBase", seedBase);
        report.addProperty("randomizeFirstPlayer", randomizeFirstPlayer);
        report.addProperty("maxSnapshotsPerGame", maxSnapshotsPerGame);
        report.addProperty("timeoutSeconds", timeoutSeconds);
        report.addProperty("quiet", quiet);
        report.addProperty("progressEvery", progressEvery);
        report.addProperty("llmStrategy", llmStrategy);
        report.addProperty("controlLlmStrategy", controlLlmStrategy);
        report.addProperty("treatmentLlmStrategy", treatmentLlmStrategy);
        report.addProperty("localRankerModelPath", rankerModelPathForReport(llmStrategy, localRankerModelPath));
        report.addProperty("controlLocalRankerModelPath",
                rankerModelPathForReport(controlLlmStrategy, controlLocalRankerModelPath));
        report.addProperty("treatmentLocalRankerModelPath",
                rankerModelPathForReport(treatmentLlmStrategy, treatmentLocalRankerModelPath));
        report.addProperty("tracePath", tracePath == null ? "" : tracePath.toString());
        report.addProperty("traceSchema", tracePath == null ? "" : traceSchema());
        report.addProperty("llmEnabled", DeepSeekClient.enabled());
        report.addProperty("llmProvider", DeepSeekClient.provider());
        report.addProperty("llmModel", DeepSeekClient.model());
        report.addProperty("llmFallbackModel", DeepSeekClient.fallbackModel());
        report.addProperty("note",
                "Each pair reuses the same deck seed, first-player setting, and hard-AI seed. "
                        + "Remote LLM sampling can still add model-side nondeterminism.");
        report.add("metrics", metrics.toJson());
        report.add("pairs", pairReports);
        report.add("summary", summary(pairReports));
        report.add("headToHeadSummary", headToHeadSummary(pairReports, focusSeat));

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, GSON.toJson(report), StandardCharsets.UTF_8);
        if (quiet) {
            System.out.println("Paired seed policy report: " + output.toAbsolutePath());
            JsonObject compact = new JsonObject();
            compact.add("summary", summary(pairReports));
            compact.add("headToHeadSummary", headToHeadSummary(pairReports, focusSeat));
            System.out.println(COMPACT_GSON.toJson(compact));
        } else {
            System.out.println(GSON.toJson(report));
            System.out.println("Paired seed policy report: " + output.toAbsolutePath());
        }
    }

    private static RunSummary runGame(
            String sessionId,
            int gameIndex,
            String label,
            String lineup,
            int focusSeat,
            boolean randomizeFirstPlayer,
            int maxSnapshots,
            long timeoutSeconds,
            String llmStrategy,
            String localRankerModelPath,
            Path tracePath) {
        SnapshotSubject subject = new SnapshotSubject(gameIndex, label, maxSnapshots);
        try (TraceBundle trace = traceBundle(tracePath, sessionId, llmStrategy);
                StrategyBundle strategy = strategyBundle(
                        llmStrategy,
                        localRankerModelPath,
                        trace.sink(),
                        sessionId)) {
            GameController controller = new GameController(subject);
            try {
                configureLlmStrategy(controller, strategy);
                subject.controller = controller;

                StartSessionRequest req = new StartSessionRequest();
                req.setSessionId(sessionId);
                req.setGameMode("CUSTOM");
                req.setCustomLineup(lineup);
                req.setPlayerCount(splitLineup(lineup).size());
                req.setRandomizeFirstPlayer(randomizeFirstPlayer);

                controller.startNewSession(req);
                await(subject, timeoutSeconds, controller);
                GameStateSnapshot snapshot = subject.last.get();
                if (trace.sink() != null) {
                    trace.sink().recordGameResult(sessionId, snapshot);
                }
                List<PlayerResult> playersBySeat = playerResultsBySeat(snapshot);
                List<PlayerResult> playersByRank = new ArrayList<>(playersBySeat);
                playersByRank.sort(Comparator
                        .comparingInt(PlayerResult::boardRank)
                        .thenComparing(Comparator.comparingInt(PlayerResult::boardScore).reversed())
                        .thenComparing(PlayerResult::playerId));
                PlayerResult focus = focusPlayer(playersBySeat, focusSeat);
                return new RunSummary(
                        label,
                        lineup,
                        sessionId,
                        subject.count,
                        subject.timedOut,
                        snapshot != null && snapshot.isGameOver(),
                        snapshot == null ? "NO_SNAPSHOT" : nullTo(snapshot.getPhase(), "UNKNOWN"),
                        snapshot == null ? "NO_SNAPSHOT" : endReason(snapshot),
                        winnerPlayerId(snapshot),
                        focus,
                        playersBySeat,
                        playersByRank,
                        snapshot == null ? null : snapshot.getLastActionSummary());
            } finally {
                controller.shutdown();
            }
        }
    }

    private static void await(SnapshotSubject subject, long timeoutSeconds, GameController controller) {
        try {
            boolean finished = subject.done.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                subject.timedOut = true;
                controller.forceEndSession("PAIRED_SEED_TIMEOUT");
                subject.done.await(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subject.timedOut = true;
            controller.forceEndSession("PAIRED_SEED_INTERRUPTED");
        }
    }

    private static PairComparison compare(PlayerResult control, PlayerResult treatment) {
        if (control == null && treatment == null) {
            return new PairComparison("tie", "no focus-seat result in either run", 0, 0);
        }
        if (control == null) {
            return new PairComparison("treatment", "control focus-seat result missing", 0, treatment.boardScore());
        }
        if (treatment == null) {
            return new PairComparison("control", "treatment focus-seat result missing", control.boardScore(), 0);
        }
        if (control.naturalWinner() != treatment.naturalWinner()) {
            return treatment.naturalWinner()
                    ? new PairComparison("treatment", "focus seat achieved a natural win", control.boardScore(), treatment.boardScore())
                    : new PairComparison("control", "control focus seat achieved a natural win", control.boardScore(), treatment.boardScore());
        }
        if (control.boardRank() != treatment.boardRank()) {
            return treatment.boardRank() < control.boardRank()
                    ? new PairComparison("treatment", "focus seat finished with a better board rank", control.boardScore(), treatment.boardScore())
                    : new PairComparison("control", "control focus seat finished with a better board rank", control.boardScore(), treatment.boardScore());
        }
        return new PairComparison(
                "tie",
                "focus seat tied by natural win and board rank; board score is diagnostic only",
                control.boardScore(),
                treatment.boardScore());
    }

    private static JsonObject pairReport(
            int game,
            long deckSeed,
            boolean randomizeFirstPlayer,
            long firstPlayerSeed,
            long aiSeed,
            RunSummary control,
            RunSummary treatment,
            PairComparison comparison) {
        JsonObject row = new JsonObject();
        row.addProperty("game", game);
        row.addProperty("deckSeed", deckSeed);
        row.addProperty("randomizeFirstPlayer", randomizeFirstPlayer);
        row.addProperty("firstPlayerSeed", randomizeFirstPlayer ? firstPlayerSeed : null);
        row.addProperty("aiSeed", aiSeed);
        row.add("control", runJson(control));
        row.add("treatment", runJson(treatment));
        row.add("comparison", GSON.toJsonTree(comparison));
        return row;
    }

    private static JsonObject runJson(RunSummary run) {
        JsonObject o = new JsonObject();
        o.addProperty("label", run.label());
        o.addProperty("lineup", run.lineup());
        o.addProperty("sessionId", run.sessionId());
        o.addProperty("snapshots", run.snapshots());
        o.addProperty("timedOut", run.timedOut());
        o.addProperty("gameOver", run.gameOver());
        o.addProperty("finalPhase", run.finalPhase());
        o.addProperty("endReason", run.endReason());
        o.addProperty("winnerPlayerId", run.winnerPlayerId());
        o.add("focusPlayer", GSON.toJsonTree(run.focusPlayer()));
        o.add("playersBySeat", GSON.toJsonTree(run.playersBySeat()));
        o.add("playersByBoardRank", GSON.toJsonTree(run.playersByBoardRank()));
        o.addProperty("lastActionSummary", run.lastActionSummary());
        return o;
    }

    private static void printProgressIfNeeded(
            boolean quiet,
            PrintStream out,
            int progressEvery,
            int completed,
            int total,
            JsonArray pairReports) {
        if (!quiet || progressEvery <= 0 || completed % progressEvery != 0 || out == null) {
            return;
        }
        JsonObject summary = summary(pairReports);
        JsonObject outcomes = summary.getAsJsonObject("pairOutcomes");
        out.println("paired progress " + completed + "/" + total
                + " treatment=" + intValue(outcomes, "treatment")
                + " control=" + intValue(outcomes, "control")
                + " tie=" + intValue(outcomes, "tie"));
    }

    private static JsonObject summary(JsonArray pairReports) {
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        int treatmentNaturalWins = 0;
        int controlNaturalWins = 0;
        int completedRuns = 0;
        int treatmentScoreDeltaSum = 0;
        List<Integer> treatmentScoreDeltas = new ArrayList<>();
        List<Integer> treatmentRankDeltas = new ArrayList<>();
        for (int i = 0; i < pairReports.size(); i++) {
            JsonObject pair = pairReports.get(i).getAsJsonObject();
            JsonObject comparison = pair.getAsJsonObject("comparison");
            String winner = comparison.get("winner").getAsString();
            outcomes.put(winner, outcomes.getOrDefault(winner, 0) + 1);
            int scoreDelta = comparison.get("treatmentScore").getAsInt()
                    - comparison.get("controlScore").getAsInt();
            treatmentScoreDeltaSum += scoreDelta;
            treatmentScoreDeltas.add(scoreDelta);
            JsonObject controlFocus = pair.getAsJsonObject("control").getAsJsonObject("focusPlayer");
            JsonObject treatmentFocus = pair.getAsJsonObject("treatment").getAsJsonObject("focusPlayer");
            treatmentRankDeltas.add(intValue(controlFocus, "boardRank")
                    - intValue(treatmentFocus, "boardRank"));
            if (controlFocus != null && controlFocus.has("naturalWinner")
                    && controlFocus.get("naturalWinner").getAsBoolean()) {
                controlNaturalWins++;
            }
            if (treatmentFocus != null && treatmentFocus.has("naturalWinner")
                    && treatmentFocus.get("naturalWinner").getAsBoolean()) {
                treatmentNaturalWins++;
            }
            if (pair.getAsJsonObject("control").get("gameOver").getAsBoolean()) {
                completedRuns++;
            }
            if (pair.getAsJsonObject("treatment").get("gameOver").getAsBoolean()) {
                completedRuns++;
            }
        }
        JsonObject summary = new JsonObject();
        JsonObject outcomeJson = new JsonObject();
        for (Map.Entry<String, Integer> entry : outcomes.entrySet()) {
            outcomeJson.addProperty(entry.getKey(), entry.getValue());
        }
        summary.add("pairOutcomes", outcomeJson);
        summary.addProperty("treatmentFocusNaturalWins", treatmentNaturalWins);
        summary.addProperty("controlFocusNaturalWins", controlNaturalWins);
        summary.addProperty("completedRuns", completedRuns);
        summary.addProperty("averageTreatmentMinusControlScore",
                pairReports.isEmpty() ? 0d : treatmentScoreDeltaSum / (double) pairReports.size());
        summary.addProperty("medianTreatmentMinusControlScore", median(treatmentScoreDeltas));
        summary.addProperty("trimmedMeanTreatmentMinusControlScore",
                trimmedMean(treatmentScoreDeltas, 0.10d));
        summary.addProperty("treatmentMinusControlScoreP10", percentile(treatmentScoreDeltas, 0.10d));
        summary.addProperty("treatmentMinusControlScoreP90", percentile(treatmentScoreDeltas, 0.90d));
        summary.addProperty("averageTreatmentMinusControlRankDelta", average(treatmentRankDeltas));
        summary.addProperty("medianTreatmentMinusControlRankDelta", median(treatmentRankDeltas));
        return summary;
    }

    private static JsonObject headToHeadSummary(JsonArray pairReports, int focusSeat) {
        int treatmentFocusWins = 0;
        int treatmentOpponentWins = 0;
        int treatmentNoNaturalWinner = 0;
        int controlFocusWins = 0;
        int controlOpponentWins = 0;
        int controlNoNaturalWinner = 0;
        int treatmentScoreDeltaSum = 0;
        List<Integer> treatmentScoreDeltas = new ArrayList<>();
        List<Integer> treatmentRankDeltas = new ArrayList<>();
        for (int i = 0; i < pairReports.size(); i++) {
            JsonObject pair = pairReports.get(i).getAsJsonObject();
            JsonObject treatment = pair.getAsJsonObject("treatment");
            JsonObject control = pair.getAsJsonObject("control");
            JsonObject treatmentFocus = treatment.getAsJsonObject("focusPlayer");
            JsonObject controlFocus = control.getAsJsonObject("focusPlayer");

            String treatmentFocusId = string(treatmentFocus, "playerId");
            String treatmentWinner = string(treatment, "winnerPlayerId");
            if (!treatmentWinner.isBlank() && treatmentWinner.equals(treatmentFocusId)) {
                treatmentFocusWins++;
            } else if (!treatmentWinner.isBlank()) {
                treatmentOpponentWins++;
            } else {
                treatmentNoNaturalWinner++;
            }

            String controlFocusId = string(controlFocus, "playerId");
            String controlWinner = string(control, "winnerPlayerId");
            if (!controlWinner.isBlank() && controlWinner.equals(controlFocusId)) {
                controlFocusWins++;
            } else if (!controlWinner.isBlank()) {
                controlOpponentWins++;
            } else {
                controlNoNaturalWinner++;
            }

            int scoreDelta = intValue(treatmentFocus, "boardScore")
                    - intValue(controlFocus, "boardScore");
            treatmentScoreDeltaSum += scoreDelta;
            treatmentScoreDeltas.add(scoreDelta);
            treatmentRankDeltas.add(intValue(controlFocus, "boardRank")
                    - intValue(treatmentFocus, "boardRank"));
        }
        JsonObject out = new JsonObject();
        out.addProperty("focusSeat", focusSeat);
        out.addProperty("treatmentFocusNaturalWins", treatmentFocusWins);
        out.addProperty("treatmentOpponentNaturalWins", treatmentOpponentWins);
        out.addProperty("treatmentNoNaturalWinner", treatmentNoNaturalWinner);
        out.addProperty("controlFocusNaturalWins", controlFocusWins);
        out.addProperty("controlOpponentNaturalWins", controlOpponentWins);
        out.addProperty("controlNoNaturalWinner", controlNoNaturalWinner);
        out.addProperty("treatmentHeadToHeadWinRate",
                pairReports.isEmpty() ? 0d : treatmentFocusWins / (double) pairReports.size());
        out.addProperty("controlHeadToHeadWinRate",
                pairReports.isEmpty() ? 0d : controlFocusWins / (double) pairReports.size());
        out.addProperty("averageTreatmentMinusControlFocusScore",
                pairReports.isEmpty() ? 0d : treatmentScoreDeltaSum / (double) pairReports.size());
        out.addProperty("medianTreatmentMinusControlFocusScore", median(treatmentScoreDeltas));
        out.addProperty("trimmedMeanTreatmentMinusControlFocusScore",
                trimmedMean(treatmentScoreDeltas, 0.10d));
        out.addProperty("averageTreatmentMinusControlRankDelta", average(treatmentRankDeltas));
        out.addProperty("medianTreatmentMinusControlRankDelta", median(treatmentRankDeltas));
        return out;
    }

    private static List<PlayerResult> playerResultsBySeat(GameStateSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        List<PlayerDraft> drafts = new ArrayList<>();
        int seat = 1;
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            drafts.add(new PlayerDraft(
                    seat++,
                    player.getPlayerId(),
                    player.getDisplayName(),
                    teamFor(player),
                    boardScore(player),
                    player.getCompletePropertySets(),
                    player.getPropertyCount(),
                    player.getBankTotalValueM(),
                    player.getHandCount(),
                    player.getPlayerId().equals(winnerPlayerId(snapshot))
                            && snapshot.getForceEndReason() == null));
        }
        List<PlayerDraft> ranked = new ArrayList<>(drafts);
        ranked.sort(Comparator
                .comparingInt(PlayerDraft::boardScore).reversed()
                .thenComparing(PlayerDraft::playerId));
        Map<String, Integer> rankByPlayer = new HashMap<>();
        int rank = 1;
        int seen = 0;
        int previousScore = Integer.MIN_VALUE;
        for (PlayerDraft draft : ranked) {
            seen++;
            if (draft.boardScore() != previousScore) {
                rank = seen;
                previousScore = draft.boardScore();
            }
            rankByPlayer.put(draft.playerId(), rank);
        }
        List<PlayerResult> results = new ArrayList<>();
        for (PlayerDraft draft : drafts) {
            results.add(new PlayerResult(
                    draft.seat(),
                    draft.playerId(),
                    draft.displayName(),
                    draft.team(),
                    draft.boardScore(),
                    rankByPlayer.getOrDefault(draft.playerId(), drafts.size() + 1),
                    draft.completeSets(),
                    draft.propertyCount(),
                    draft.bankM(),
                    draft.handCount(),
                    draft.naturalWinner()));
        }
        return results;
    }

    private static int boardScore(GameStateSnapshot.PlayerPublicSummary player) {
        int propertyProgress = 0;
        for (PropertyColorProgress progress : player.getPropertyColorProgress()) {
            propertyProgress += Math.min(progress.getEffectiveCount(), progress.getNeed());
        }
        return player.getCompletePropertySets() * 1000
                + propertyProgress * 80
                + player.getPropertyCount() * 20
                + player.getBankTotalValueM() * 10
                + player.getHandCount();
    }

    private static PlayerResult focusPlayer(List<PlayerResult> playersBySeat, int focusSeat) {
        if (playersBySeat == null || focusSeat < 1 || focusSeat > playersBySeat.size()) {
            return null;
        }
        return playersBySeat.get(focusSeat - 1);
    }

    private static String winnerPlayerId(GameStateSnapshot snapshot) {
        if (snapshot == null || snapshot.getForceEndReason() != null) {
            return null;
        }
        String winner = null;
        int winners = 0;
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            if (player.getCompletePropertySets() >= 3) {
                winner = player.getPlayerId();
                winners++;
            }
        }
        return winners == 1 ? winner : null;
    }

    private static String endReason(GameStateSnapshot snapshot) {
        if (snapshot.getForceEndReason() != null) {
            return snapshot.getForceEndReason();
        }
        return snapshot.isGameOver() ? "NATURAL_WIN" : "RUNNING";
    }

    private static String teamFor(GameStateSnapshot.PlayerPublicSummary player) {
        String name = player.getDisplayName() == null ? "" : player.getDisplayName().toLowerCase(Locale.ROOT);
        if (name.contains("deepseek")) {
            return "deepseek";
        }
        if (name.contains("openai") || name.contains("gpt")) {
            return "openai";
        }
        if (name.contains("lookahead") || name.contains("search")) {
            return "lookahead";
        }
        if (name.contains("hard")) {
            return "hard";
        }
        if (name.contains("normal")) {
            return "normal";
        }
        if (name.contains("easy")) {
            return "easy";
        }
        return "human";
    }

    private static void configureLlmStrategy(
            GameController controller,
            StrategyBundle strategy) {
        if (strategy.factory() != null) {
            controller.setLlmAiStrategyFactory(strategy.factory());
        }
    }

    private static StrategyBundle strategyBundle(
            String llmStrategy,
            String localRankerModelPath,
            DecisionTraceSink traceSink,
            String sessionId) {
        if ("remote".equals(llmStrategy) || "llm".equals(llmStrategy) || "deepseek".equals(llmStrategy)) {
            if (traceSink == null) {
                return StrategyBundle.empty();
            }
            return new StrategyBundle(
                    playerNumber -> new DeepSeekAiPlayStrategy(traceSink, sessionId),
                    null);
        }
        if ("local_ranker".equals(llmStrategy) || "ranker".equals(llmStrategy)) {
            Path modelPath = Path.of(requireLocalRankerModelPath(localRankerModelPath));
            return new StrategyBundle(playerNumber -> new LocalRankerAiPlayStrategy(
                    modelPath,
                    traceSink == null ? DecisionTraceSink.NONE : traceSink,
                    sessionId), null);
        }
        if ("strategic_heuristic".equals(llmStrategy) || "strategic".equals(llmStrategy)) {
            DecisionBroker broker = new DecisionBroker(
                    new StrategicHeuristicDecisionTeacher(),
                    Math.max(1, Integer.getInteger("monopoly.strategicHeuristic.batchSize", 1)),
                    Duration.ofMillis(Math.max(0L, Long.getLong("monopoly.strategicHeuristic.batchWaitMs", 0L))),
                    traceSink == null ? DecisionTraceSink.NONE : traceSink);
            return new StrategyBundle(
                    playerNumber -> new BrokeredAiPlayStrategy(broker, sessionId),
                    broker);
        }
        if ("lookahead".equals(llmStrategy) || "search".equals(llmStrategy) || "rollout".equals(llmStrategy)) {
            return new StrategyBundle(
                    playerNumber -> new SearchLookaheadAiPlayStrategy(
                            traceSink == null ? DecisionTraceSink.NONE : traceSink,
                            sessionId),
                    null);
        }
        throw new IllegalArgumentException(
                "unsupported monopoly.pairedBattle.llmStrategy: " + llmStrategy
                        + " (expected remote, local_ranker, strategic_heuristic, or lookahead)");
    }

    private static String strategyProperty(String key, String fallback) {
        return System.getProperty(key, fallback)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String modelPathProperty(String key, String fallback) {
        return System.getProperty(key, fallback).trim();
    }

    private static String rankerModelPathForReport(String llmStrategy, String modelPath) {
        if (!"local_ranker".equals(llmStrategy) && !"ranker".equals(llmStrategy)) {
            return "";
        }
        return requireLocalRankerModelPath(modelPath);
    }

    private static String requireLocalRankerModelPath(String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException(
                    "monopoly.localRanker.modelPath is required when pairedBattle llmStrategy=local_ranker "
                            + "(or set monopoly.pairedBattle.controlLocalRankerModelPath / "
                            + "monopoly.pairedBattle.treatmentLocalRankerModelPath)");
        }
        return path;
    }

    private static TraceBundle traceBundle(
            Path tracePath,
            String sessionId,
            String llmStrategy) {
        if (tracePath == null) {
            return TraceBundle.empty();
        }
        if (!supportsDecisionTrace(llmStrategy)) {
            throw new IllegalArgumentException(
                    "pairedBattle trace currently requires llmStrategy=deepseek, local_ranker, "
                            + "strategic_heuristic, or lookahead");
        }
        try {
            DecisionTraceSink sink = isOutcomeTrace()
                    ? new OutcomeDecisionTraceSink(tracePath)
                    : new JsonlDecisionTraceSink(tracePath);
            return new TraceBundle(sink, sessionId);
        } catch (Exception e) {
            throw new IllegalStateException("failed to open paired battle trace: " + tracePath, e);
        }
    }

    private static Path tracePath() {
        String raw = System.getProperty("monopoly.pairedBattle.tracePath", "").trim();
        return raw.isBlank() ? null : Path.of(raw);
    }

    private static boolean isTraceOverwrite() {
        String mode = System.getProperty("monopoly.pairedBattle.traceMode", "fail_if_exists")
                .trim()
                .toLowerCase(Locale.ROOT);
        return "overwrite".equals(mode) || "replace".equals(mode) || "truncate".equals(mode);
    }

    private static String traceSchema() {
        return System.getProperty("monopoly.pairedBattle.traceSchema", "decision")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isOutcomeTrace() {
        String schema = traceSchema();
        return "outcome".equals(schema) || "outcome_weighted".equals(schema);
    }

    private static boolean supportsDecisionTrace(String llmStrategy) {
        return "remote".equals(llmStrategy)
                || "llm".equals(llmStrategy)
                || "deepseek".equals(llmStrategy)
                || "local_ranker".equals(llmStrategy)
                || "ranker".equals(llmStrategy)
                || "strategic_heuristic".equals(llmStrategy)
                || "strategic".equals(llmStrategy)
                || "lookahead".equals(llmStrategy)
                || "search".equals(llmStrategy)
                || "rollout".equals(llmStrategy);
    }

    private static void configureSeedProperties(
            long deckSeed,
            long firstPlayerSeed,
            long aiSeed,
            boolean randomizeFirstPlayer) {
        System.setProperty(DECK_SEED_PROPERTY, Long.toString(deckSeed));
        System.setProperty(AI_SEED_PROPERTY, Long.toString(aiSeed));
        if (randomizeFirstPlayer) {
            System.setProperty(FIRST_PLAYER_SEED_PROPERTY, Long.toString(firstPlayerSeed));
        } else {
            System.clearProperty(FIRST_PLAYER_SEED_PROPERTY);
        }
    }

    private static Map<String, String> snapshotProperties() {
        Map<String, String> values = new HashMap<>();
        for (String key : List.of(DECK_SEED_PROPERTY, FIRST_PLAYER_SEED_PROPERTY, AI_SEED_PROPERTY)) {
            values.put(key, System.getProperty(key));
        }
        return values;
    }

    private static void restoreProperties(Map<String, String> values) {
        for (String key : List.of(DECK_SEED_PROPERTY, FIRST_PLAYER_SEED_PROPERTY, AI_SEED_PROPERTY)) {
            String value = values.get(key);
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }
    }

    private static List<String> splitLineup(String lineup) {
        List<String> roles = new ArrayList<>();
        if (lineup != null) {
            for (String token : lineup.split("[,;\\s]+")) {
                if (!token.isBlank()) {
                    roles.add(token.trim());
                }
            }
        }
        return roles.isEmpty() ? List.of("hard", "hard") : roles;
    }

    private static String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static int intValue(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0;
        }
        return object.get(key).getAsInt();
    }

    private static double average(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        long sum = 0L;
        for (int value : values) {
            sum += value;
        }
        return sum / (double) values.size();
    }

    private static double median(List<Integer> values) {
        return percentile(values, 0.50d);
    }

    private static double trimmedMean(List<Integer> values, double trimFraction) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int trim = (int) Math.floor(sorted.size() * trimFraction);
        if (trim <= 0 || trim * 2 >= sorted.size()) {
            return average(sorted);
        }
        long sum = 0L;
        int count = 0;
        for (int i = trim; i < sorted.size() - trim; i++) {
            sum += sorted.get(i);
            count++;
        }
        return count == 0 ? average(sorted) : sum / (double) count;
    }

    private static double percentile(List<Integer> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        double bounded = Math.max(0d, Math.min(1d, percentile));
        double position = bounded * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double lowerWeight = upper - position;
        double upperWeight = position - lower;
        return sorted.get(lower) * lowerWeight + sorted.get(upper) * upperWeight;
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
    }

    private record RunSummary(
            String label,
            String lineup,
            String sessionId,
            int snapshots,
            boolean timedOut,
            boolean gameOver,
            String finalPhase,
            String endReason,
            String winnerPlayerId,
            PlayerResult focusPlayer,
            List<PlayerResult> playersBySeat,
            List<PlayerResult> playersByBoardRank,
            String lastActionSummary) {
    }

    private record PlayerDraft(
            int seat,
            String playerId,
            String displayName,
            String team,
            int boardScore,
            int completeSets,
            int propertyCount,
            int bankM,
            int handCount,
            boolean naturalWinner) {
    }

    private record PlayerResult(
            int seat,
            String playerId,
            String displayName,
            String team,
            int boardScore,
            int boardRank,
            int completeSets,
            int propertyCount,
            int bankM,
            int handCount,
            boolean naturalWinner) {
    }

    private record PairComparison(
            String winner,
            String reason,
            int controlScore,
            int treatmentScore) {
    }

    private record TraceBundle(
            DecisionTraceSink sink,
            String sessionId) implements AutoCloseable {

        private static TraceBundle empty() {
            return new TraceBundle(null, "");
        }

        @Override
        public void close() {
            if (sink != null) {
                try {
                    sink.close();
                } catch (Exception e) {
                    throw new IllegalStateException("failed to close paired battle trace", e);
                }
            }
        }
    }

    private record StrategyBundle(
            IntFunction<AiPlayStrategy> factory,
            AutoCloseable closeable) implements AutoCloseable {

        private static StrategyBundle empty() {
            return new StrategyBundle(null, null);
        }

        @Override
        public void close() {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    throw new IllegalStateException("failed to close paired battle strategy", e);
                }
            }
        }
    }

    private static final class SnapshotSubject implements GameUpdateSubject {
        private final AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final int gameIndex;
        private final String label;
        private final int maxSnapshotsForGame;
        private GameController controller;
        private int count;
        private boolean timedOut;
        private boolean forced;

        private SnapshotSubject(int gameIndex, String label, int maxSnapshotsForGame) {
            this.gameIndex = gameIndex;
            this.label = label;
            this.maxSnapshotsForGame = Math.max(1, maxSnapshotsForGame);
        }

        @Override
        public void registerObserver(GameUpdateObserver observer) {
        }

        @Override
        public void unregisterObserver(GameUpdateObserver observer) {
        }

        @Override
        public void notifyStateChanged(GameStateSnapshot snapshot) {
            last.set(snapshot);
            count++;
            if (snapshot == null) {
                return;
            }
            AiBattleLogger.log("PairedSeed",
                    "game=" + gameIndex
                            + " label=" + label
                            + " n=" + count
                            + " phase=" + snapshot.getPhase()
                            + " turn=" + snapshot.getCurrentPlayerId()
                            + " summary=" + snapshot.getLastActionSummary());
            if (snapshot.isGameOver()) {
                done.countDown();
                return;
            }
            if (!forced && isExhaustedState(snapshot) && controller != null) {
                forced = true;
                controller.forceEndSession("PAIRED_SEED_EXHAUSTED_STATE");
                return;
            }
            if (!forced && count >= maxSnapshotsForGame && controller != null) {
                forced = true;
                controller.forceEndSession("PAIRED_SEED_SNAPSHOT_LIMIT");
            }
        }

        private static boolean isExhaustedState(GameStateSnapshot snapshot) {
            return snapshot.getDrawPileCount() <= 0 && totalHandCount(snapshot) <= 0;
        }

        private static int totalHandCount(GameStateSnapshot snapshot) {
            int total = 0;
            for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
                total += player.getHandCount();
            }
            return total;
        }
    }

    private static final class Metrics implements AiBattleLogger.MetricsSink {
        private int decisions;
        private int deepSeekUsageCalls;
        private int fallbackUsageCalls;
        private int malformedDecisions;
        private int invalidCandidates;
        private int skippedResponseCalls;
        private int totalPromptTokens;
        private int totalCompletionTokens;

        @Override
        public void onLog(String category, String message) {
            if ("Decision".equals(category)) {
                decisions++;
                return;
            }
            if (!"DeepSeek".equals(category) || message == null) {
                return;
            }
            if (message.startsWith("usage model=")) {
                deepSeekUsageCalls++;
                if (message.startsWith("usage model=" + DeepSeekClient.fallbackModel())) {
                    fallbackUsageCalls++;
                }
                totalPromptTokens += intAfter(message, "\"prompt_tokens\":");
                totalCompletionTokens += intAfter(message, "\"completion_tokens\":");
                return;
            }
            if (message.contains("malformed decision")) {
                malformedDecisions++;
            }
            if (message.contains("invalid model candidate")) {
                invalidCandidates++;
            }
            if (message.contains("skipped response model")) {
                skippedResponseCalls++;
            }
        }

        private JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("decisions", decisions);
            o.addProperty("deepSeekUsageCalls", deepSeekUsageCalls);
            o.addProperty("fallbackUsageCalls", fallbackUsageCalls);
            o.addProperty("malformedDecisions", malformedDecisions);
            o.addProperty("invalidCandidates", invalidCandidates);
            o.addProperty("skippedResponseCalls", skippedResponseCalls);
            o.addProperty("totalPromptTokens", totalPromptTokens);
            o.addProperty("totalCompletionTokens", totalCompletionTokens);
            o.addProperty("totalTokens", totalPromptTokens + totalCompletionTokens);
            return o;
        }

        private static int intAfter(String s, String marker) {
            int start = s.indexOf(marker);
            if (start < 0) {
                return 0;
            }
            start += marker.length();
            int end = start;
            while (end < s.length() && Character.isDigit(s.charAt(end))) {
                end++;
            }
            if (end == start) {
                return 0;
            }
            try {
                return Integer.parseInt(s.substring(start, end));
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
    }
}
