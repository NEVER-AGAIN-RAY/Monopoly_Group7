package com.monopoly.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiBattleLogger;
import com.monopoly.pattern.strategy.DeepSeekClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs mixed custom AI tables, e.g. hard,hard,llm,llm, and writes a compact JSON report.
 */
public final class MixedAiBattleExperimentRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MixedAiBattleExperimentRunner() {
    }

    public static void main(String[] args) throws IOException {
        int games = Math.max(1, Integer.getInteger("monopoly.mixedBattle.games", 1));
        int maxSnapshotsPerGame = Math.max(20, Integer.getInteger(
                "monopoly.mixedBattle.maxSnapshotsPerGame", 180));
        long timeoutSeconds = Math.max(10L, Long.getLong("monopoly.mixedBattle.timeoutSeconds", 180L));
        String lineup = System.getProperty("monopoly.mixedBattle.lineup", "hard,hard,llm,llm");
        String sessionPrefix = System.getProperty("monopoly.mixedBattle.sessionId",
                "mixed-battle-" + System.currentTimeMillis());
        Path output = Path.of(System.getProperty("monopoly.mixedBattle.output",
                "models/evaluation/mixed-hard-deepseek-" + timestamp() + ".json"));

        Metrics metrics = new Metrics();
        AiBattleLogger.setMetricsSink(metrics);
        JsonArray gameReports = new JsonArray();
        try {
            for (int game = 1; game <= games; game++) {
                SnapshotSubject subject = new SnapshotSubject(game, maxSnapshotsPerGame);
                GameController controller = new GameController(subject);
                subject.controller = controller;

                StartSessionRequest req = new StartSessionRequest();
                req.setSessionId(sessionPrefix + "-g" + game);
                req.setGameMode("CUSTOM");
                req.setCustomLineup(lineup);
                req.setPlayerCount(splitLineup(lineup).size());
                req.setRandomizeFirstPlayer(true);

                controller.startNewSession(req);
                await(subject, timeoutSeconds, controller);
                gameReports.add(gameReport(game, subject.last.get(), subject.count, subject.timedOut));
            }
        } finally {
            AiBattleLogger.setMetricsSink(null);
        }

        JsonObject report = new JsonObject();
        report.addProperty("timestamp", Instant.now().toString());
        report.addProperty("lineup", lineup);
        report.addProperty("gamesRequested", games);
        report.addProperty("maxSnapshotsPerGame", maxSnapshotsPerGame);
        report.addProperty("timeoutSeconds", timeoutSeconds);
        report.addProperty("llmEnabled", DeepSeekClient.enabled());
        report.addProperty("llmProvider", DeepSeekClient.provider());
        report.addProperty("llmModel", DeepSeekClient.model());
        report.addProperty("llmFallbackModel", DeepSeekClient.fallbackModel());
        report.addProperty("preferFallbackForStrictJson", DeepSeekClient.preferFallbackForStrictJson());
        report.add("metrics", metrics.toJson());
        report.add("games", gameReports);
        report.add("summary", summary(gameReports));

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, GSON.toJson(report), StandardCharsets.UTF_8);
        System.out.println(GSON.toJson(report));
        System.out.println("Mixed AI battle report: " + output.toAbsolutePath());
    }

    private static void await(SnapshotSubject subject, long timeoutSeconds, GameController controller) {
        try {
            boolean finished = subject.done.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                subject.timedOut = true;
                controller.forceEndSession("MIXED_BATTLE_TIMEOUT");
                subject.done.await(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subject.timedOut = true;
            controller.forceEndSession("MIXED_BATTLE_INTERRUPTED");
        }
    }

    private static JsonObject gameReport(
            int game,
            GameStateSnapshot snapshot,
            int snapshots,
            boolean timedOut) {
        JsonObject row = new JsonObject();
        row.addProperty("game", game);
        row.addProperty("snapshots", snapshots);
        row.addProperty("timedOut", timedOut);
        row.addProperty("gameOver", snapshot != null && snapshot.isGameOver());
        row.addProperty("phase", snapshot == null ? null : snapshot.getPhase());
        row.addProperty("forceEndReason", snapshot == null ? null : snapshot.getForceEndReason());
        row.addProperty("lastActionSummary", snapshot == null ? null : snapshot.getLastActionSummary());
        JsonArray players = new JsonArray();
        if (snapshot != null) {
            List<GameStateSnapshot.PlayerPublicSummary> ranked = new ArrayList<>(snapshot.getPlayers());
            ranked.sort(Comparator
                    .comparingInt(GameStateSnapshot.PlayerPublicSummary::getCompletePropertySets).reversed()
                    .thenComparing(Comparator
                            .comparingInt(GameStateSnapshot.PlayerPublicSummary::getPropertyCount).reversed())
                    .thenComparing(Comparator
                            .comparingInt(GameStateSnapshot.PlayerPublicSummary::getBankTotalValueM).reversed()));
            int rank = 1;
            for (GameStateSnapshot.PlayerPublicSummary p : ranked) {
                JsonObject player = new JsonObject();
                player.addProperty("rank", rank++);
                player.addProperty("team", teamFor(p));
                player.addProperty("playerId", p.getPlayerId());
                player.addProperty("displayName", p.getDisplayName());
                player.addProperty("completeSets", p.getCompletePropertySets());
                player.addProperty("propertyCount", p.getPropertyCount());
                player.addProperty("bankM", p.getBankTotalValueM());
                player.addProperty("handCount", p.getHandCount());
                players.add(player);
            }
        }
        row.add("playersByBoardRank", players);
        row.addProperty("leaderTeam", players.isEmpty()
                ? null : players.get(0).getAsJsonObject().get("team").getAsString());
        return row;
    }

    private static JsonObject summary(JsonArray gameReports) {
        Map<String, Integer> leaderCounts = new LinkedHashMap<>();
        int completed = 0;
        for (int i = 0; i < gameReports.size(); i++) {
            JsonObject game = gameReports.get(i).getAsJsonObject();
            if (game.has("gameOver") && game.get("gameOver").getAsBoolean()) {
                completed++;
            }
            String leaderTeam = game.has("leaderTeam") && !game.get("leaderTeam").isJsonNull()
                    ? game.get("leaderTeam").getAsString() : "unknown";
            leaderCounts.put(leaderTeam, leaderCounts.getOrDefault(leaderTeam, 0) + 1);
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("gamesWithGameOver", completed);
        JsonObject leaders = new JsonObject();
        for (Map.Entry<String, Integer> entry : leaderCounts.entrySet()) {
            leaders.addProperty(entry.getKey(), entry.getValue());
        }
        summary.add("leaderTeamCounts", leaders);
        return summary;
    }

    private static String teamFor(GameStateSnapshot.PlayerPublicSummary player) {
        String name = player.getDisplayName() == null ? "" : player.getDisplayName().toLowerCase(Locale.ROOT);
        if (name.contains("deepseek")) {
            return "deepseek";
        }
        if (name.contains("openai") || name.contains("gpt")) {
            return "openai";
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

    private static List<String> splitLineup(String lineup) {
        List<String> roles = new ArrayList<>();
        if (lineup != null) {
            for (String token : lineup.split("[,;\\s]+")) {
                if (!token.isBlank()) {
                    roles.add(token.trim());
                }
            }
        }
        return roles.isEmpty() ? List.of("hard", "hard", "llm", "llm") : roles;
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
    }

    private static final class SnapshotSubject implements GameUpdateSubject {
        private final AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final int gameIndex;
        private final int maxSnapshotsForGame;
        private GameController controller;
        private int count;
        private boolean timedOut;

        private SnapshotSubject(int gameIndex, int maxSnapshotsForGame) {
            this.gameIndex = gameIndex;
            this.maxSnapshotsForGame = maxSnapshotsForGame;
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
            if (snapshot != null) {
                AiBattleLogger.log("MixedBattle",
                        "game=" + gameIndex
                                + " n=" + count
                                + " phase=" + snapshot.getPhase()
                                + " turn=" + snapshot.getCurrentPlayerId()
                                + " summary=" + snapshot.getLastActionSummary());
                if (!snapshot.isGameOver() && count >= maxSnapshotsForGame && controller != null) {
                    controller.forceEndSession("MIXED_BATTLE_TURN_LIMIT");
                    return;
                }
                if (snapshot.isGameOver()) {
                    done.countDown();
                }
            }
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
