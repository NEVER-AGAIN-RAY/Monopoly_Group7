package com.monopoly.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiBattleLogger;
import com.monopoly.pattern.strategy.DeepSeekClient;
import com.monopoly.pattern.strategy.HybridLookaheadPaymentRankerAiPlayStrategy;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;
import com.monopoly.simulation.DecisionTraceSink;
import com.monopoly.simulation.JsonlDecisionTraceSink;
import com.monopoly.simulation.OutcomeDecisionTraceSink;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs mixed custom AI tables, e.g. hard,hard,llm,llm, and writes a compact JSON report.
 */
public final class MixedAiBattleExperimentRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DECK_SEED_PROPERTY = "monopoly.deck.seed";
    private static final String FIRST_PLAYER_SEED_PROPERTY = "monopoly.firstPlayer.seed";
    private static final String AI_SEED_PROPERTY = "monopoly.ai.seed";
    private static final long FIRST_PLAYER_SALT = 0xD1B54A32D192ED03L;
    private static final long AI_SEED_SALT = 0x94D049BB133111EBL;

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
                "training/data/models/evaluation/mixed-hard-deepseek-" + timestamp() + ".json"));
        boolean quiet = Boolean.parseBoolean(System.getProperty("monopoly.mixedBattle.quiet", "false"));
        boolean randomizeFirstPlayer = Boolean.parseBoolean(
                System.getProperty("monopoly.mixedBattle.randomizeFirstPlayer", "true"));
        boolean seeded = Boolean.parseBoolean(System.getProperty("monopoly.mixedBattle.seeded", "true"));
        int progressEvery = Math.max(0, Integer.getInteger("monopoly.mixedBattle.progressEvery", 0));
        Path tracePath = tracePath();
        String traceSchema = traceSchema();
        Long configuredSeedBase = Long.getLong("monopoly.mixedBattle.seedBase");
        long seedBase = configuredSeedBase != null
                ? configuredSeedBase
                : ThreadLocalRandom.current().nextLong(Long.MAX_VALUE / 4L);
        List<Long> explicitDeckSeeds = explicitDeckSeeds();
        if (!seeded && !explicitDeckSeeds.isEmpty()) {
            throw new IllegalArgumentException("monopoly.mixedBattle.seedList requires monopoly.mixedBattle.seeded=true");
        }
        int gamesToRun = explicitDeckSeeds.isEmpty() ? games : explicitDeckSeeds.size();
        if (tracePath != null && isTraceOverwrite()) {
            Files.deleteIfExists(tracePath);
        }

        Metrics metrics = new Metrics();
        AiBattleLogger.setMetricsSink(metrics);
        PrintStream originalOut = System.out;
        JsonArray gameReports = new JsonArray();
        List<String> lineupRoles = splitLineup(lineup);
        Map<String, String> originalSeedProperties = snapshotSeedProperties();
        try (TraceBundle trace = traceBundle(tracePath)) {
            if (quiet) {
                System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            }
            for (int game = 1; game <= gamesToRun; game++) {
                SnapshotSubject subject = new SnapshotSubject(game, maxSnapshotsPerGame);
                GameController controller = new GameController(subject);
                subject.controller = controller;

                StartSessionRequest req = new StartSessionRequest();
                req.setSessionId(sessionPrefix + "-g" + game);
                configureTracedLookaheadStrategy(controller, trace, req.getSessionId());
                req.setGameMode("CUSTOM");
                req.setCustomLineup(lineup);
                req.setPlayerCount(lineupRoles.size());
                req.setRandomizeFirstPlayer(randomizeFirstPlayer);

                long deckSeed = explicitDeckSeeds.isEmpty()
                        ? seedBase + game - 1L
                        : explicitDeckSeeds.get(game - 1);
                long firstPlayerSeed = deckSeed ^ FIRST_PLAYER_SALT;
                long aiSeed = deckSeed ^ AI_SEED_SALT;
                if (seeded) {
                    configureSeedProperties(deckSeed, firstPlayerSeed, aiSeed, randomizeFirstPlayer);
                } else {
                    restoreSeedProperties(originalSeedProperties);
                }
                controller.startNewSession(req);
                await(subject, timeoutSeconds, controller);
                if (trace.sink() != null) {
                    trace.sink().recordGameResult(req.getSessionId(), subject.last.get());
                }
                gameReports.add(gameReport(
                        game,
                        subject.last.get(),
                        subject.count,
                        subject.timedOut,
                        seeded,
                        deckSeed,
                        firstPlayerSeed,
                        aiSeed,
                        randomizeFirstPlayer,
                        lineupRoles));
                if (progressEvery > 0 && (game % progressEvery == 0 || game == gamesToRun)) {
                    JsonObject progress = new JsonObject();
                    progress.addProperty("event", "progress");
                    progress.addProperty("game", game);
                    progress.addProperty("gamesRequested", gamesToRun);
                    progress.addProperty("deckSeed", deckSeed);
                    progress.add("summary", summary(gameReports));
                    originalOut.println(GSON.toJson(progress));
                }
            }
        } finally {
            if (quiet) {
                System.setOut(originalOut);
            }
            AiBattleLogger.setMetricsSink(null);
            restoreSeedProperties(originalSeedProperties);
        }

        JsonObject report = new JsonObject();
        report.addProperty("timestamp", Instant.now().toString());
        report.addProperty("lineup", lineup);
        report.addProperty("gamesRequested", gamesToRun);
        report.addProperty("configuredGames", games);
        report.addProperty("maxSnapshotsPerGame", maxSnapshotsPerGame);
        report.addProperty("timeoutSeconds", timeoutSeconds);
        report.addProperty("quiet", quiet);
        report.addProperty("randomizeFirstPlayer", randomizeFirstPlayer);
        report.addProperty("progressEvery", progressEvery);
        report.addProperty("seeded", seeded);
        if (seeded) {
            report.addProperty("seedBase", seedBase);
            if (!explicitDeckSeeds.isEmpty()) {
                JsonArray seeds = new JsonArray();
                for (Long seed : explicitDeckSeeds) {
                    seeds.add(seed);
                }
                report.add("seedList", seeds);
            }
            report.addProperty("seedPolicy",
                    explicitDeckSeeds.isEmpty()
                            ? "Independent per-game deck/AI seeds are recorded for replay; this is not a same-seed paired comparison."
                            : "Explicit per-game deck seeds from monopoly.mixedBattle.seedList; AI/first-player seeds are derived from each deck seed.");
        }
        report.addProperty("tracePath", tracePath == null ? "" : tracePath.toString());
        report.addProperty("traceSchema", tracePath == null ? "" : traceSchema);
        report.addProperty("llmEnabled", DeepSeekClient.enabled());
        report.addProperty("llmProvider", DeepSeekClient.provider());
        report.addProperty("llmModel", DeepSeekClient.model());
        report.addProperty("llmFallbackModel", DeepSeekClient.fallbackModel());
        report.addProperty("preferFallbackForStrictJson", DeepSeekClient.preferFallbackForStrictJson());
        report.add("strategyProperties", strategyProperties());
        report.add("effectiveLookaheadConfig", SearchLookaheadAiPlayStrategy.effectiveConfigSnapshot());
        report.add("metrics", metrics.toJson());
        report.add("games", gameReports);
        report.add("summary", summary(gameReports));

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, GSON.toJson(report), StandardCharsets.UTF_8);
        if (quiet) {
            JsonObject compact = new JsonObject();
            compact.addProperty("timestamp", report.get("timestamp").getAsString());
            compact.addProperty("lineup", lineup);
            compact.addProperty("gamesRequested", gamesToRun);
            compact.add("summary", report.get("summary"));
            System.out.println(GSON.toJson(compact));
        } else {
            System.out.println(GSON.toJson(report));
        }
        System.out.println("Mixed AI battle report: " + output.toAbsolutePath());
    }

    private static void configureTracedLookaheadStrategy(
            GameController controller,
            TraceBundle trace,
            String sessionId) {
        if (controller == null || trace == null || trace.sink() == null) {
            configureHybridLookaheadPaymentRanker(controller);
            return;
        }
        controller.setLookaheadAiStrategyFactory(
                playerNumber -> new SearchLookaheadAiPlayStrategy(trace.sink(), sessionId));
    }

    private static void configureHybridLookaheadPaymentRanker(GameController controller) {
        if (controller == null) {
            return;
        }
        String raw = System.getProperty("monopoly.mixedBattle.lookaheadPaymentRankerModel", "").trim();
        if (raw.isBlank()) {
            return;
        }
        Path model = Path.of(raw);
        controller.setLookaheadAiStrategyFactory(
                playerNumber -> new HybridLookaheadPaymentRankerAiPlayStrategy(model));
    }

    private static TraceBundle traceBundle(Path tracePath) {
        if (tracePath == null) {
            return TraceBundle.empty();
        }
        try {
            DecisionTraceSink sink = isOutcomeTrace()
                    ? new OutcomeDecisionTraceSink(tracePath)
                    : new JsonlDecisionTraceSink(tracePath);
            return new TraceBundle(sink);
        } catch (Exception e) {
            throw new IllegalStateException("failed to open mixed battle trace: " + tracePath, e);
        }
    }

    private static Path tracePath() {
        String raw = System.getProperty("monopoly.mixedBattle.tracePath", "").trim();
        return raw.isBlank() ? null : Path.of(raw);
    }

    private static boolean isTraceOverwrite() {
        String mode = System.getProperty("monopoly.mixedBattle.traceMode", "fail_if_exists")
                .trim()
                .toLowerCase(Locale.ROOT);
        return "overwrite".equals(mode) || "replace".equals(mode) || "truncate".equals(mode);
    }

    private static String traceSchema() {
        return System.getProperty("monopoly.mixedBattle.traceSchema", "decision")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isOutcomeTrace() {
        String schema = traceSchema();
        return "outcome".equals(schema) || "outcome_weighted".equals(schema);
    }

    static List<Long> explicitDeckSeedsForTest(String raw) {
        return parseSeedList(raw);
    }

    static JsonObject initialPlayerForTest(
            boolean randomizeFirstPlayer,
            long firstPlayerSeed,
            List<String> lineupRoles) {
        return initialPlayer(randomizeFirstPlayer, firstPlayerSeed, lineupRoles);
    }

    private static List<Long> explicitDeckSeeds() {
        return parseSeedList(System.getProperty("monopoly.mixedBattle.seedList", ""));
    }

    private static List<Long> parseSeedList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (String token : raw.split("[,;\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                out.add(Long.parseLong(token.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "invalid monopoly.mixedBattle.seedList value: " + token, e);
            }
        }
        return List.copyOf(out);
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
            boolean timedOut,
            boolean seeded,
            long deckSeed,
            long firstPlayerSeed,
            long aiSeed,
            boolean randomizeFirstPlayer,
            List<String> lineupRoles) {
        JsonObject row = new JsonObject();
        row.addProperty("game", game);
        row.addProperty("snapshots", snapshots);
        row.addProperty("timedOut", timedOut);
        row.addProperty("seeded", seeded);
        if (seeded) {
            row.addProperty("deckSeed", deckSeed);
            row.addProperty("firstPlayerSeed", randomizeFirstPlayer ? firstPlayerSeed : null);
            row.addProperty("aiSeed", aiSeed);
        }
        JsonObject initialPlayer = initialPlayer(randomizeFirstPlayer, firstPlayerSeed, lineupRoles);
        row.add("initialPlayer", initialPlayer);
        row.addProperty("initialPlayerIndex", initialPlayer.get("index").getAsInt());
        row.addProperty("initialPlayerRole", initialPlayer.get("role").getAsString());
        row.addProperty("initialPlayerTeam", initialPlayer.get("team").getAsString());
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
                player.addProperty("actionZoneCount", p.getActionZoneCount());
                player.addProperty("boardScore", boardScore(p));
                player.addProperty("nearCompleteColors", nearCompleteColors(p));
                player.addProperty("maxMissingToComplete", maxMissingToComplete(p));
                players.add(player);
            }
        }
        row.add("playersByBoardRank", players);
        String naturalWinnerTeam = null;
        String naturalWinnerPlayerId = null;
        int naturalWinnerCount = 0;
        String naturalWinnerResolution = "none";
        for (int i = 0; i < players.size(); i++) {
            JsonObject player = players.get(i).getAsJsonObject();
            if (player.get("completeSets").getAsInt() >= 3) {
                naturalWinnerCount++;
                naturalWinnerTeam = player.get("team").getAsString();
                naturalWinnerPlayerId = player.get("playerId").getAsString();
            }
        }
        if (naturalWinnerCount == 1) {
            naturalWinnerResolution = "unique_complete_sets";
        } else {
            JsonObject parsedWinner = winnerFromLastAction(players, row.get("lastActionSummary"));
            if (parsedWinner != null) {
                naturalWinnerTeam = parsedWinner.get("team").getAsString();
                naturalWinnerPlayerId = parsedWinner.get("playerId").getAsString();
                naturalWinnerResolution = "last_action_summary";
            } else {
                naturalWinnerTeam = null;
                naturalWinnerPlayerId = null;
            }
        }
        row.addProperty("naturalWinnerCount", naturalWinnerCount);
        row.addProperty("naturalWinnerTeam", naturalWinnerTeam);
        row.addProperty("naturalWinnerPlayerId", naturalWinnerPlayerId);
        row.addProperty("naturalWinnerResolution", naturalWinnerResolution);
        row.addProperty("leaderTeam", players.isEmpty()
                ? null : players.get(0).getAsJsonObject().get("team").getAsString());
        return row;
    }

    private static int initialPlayerIndex(
            boolean randomizeFirstPlayer,
            long firstPlayerSeed,
            int playerCount) {
        int count = Math.max(1, playerCount);
        if (!randomizeFirstPlayer) {
            return 0;
        }
        return new Random(firstPlayerSeed).nextInt(count);
    }

    private static String roleAt(List<String> lineupRoles, int index) {
        if (lineupRoles == null || lineupRoles.isEmpty()) {
            return "";
        }
        return lineupRoles.get(Math.floorMod(index, lineupRoles.size()));
    }

    private static JsonObject initialPlayer(
            boolean randomizeFirstPlayer,
            long firstPlayerSeed,
            List<String> lineupRoles) {
        int initialPlayerIndex = initialPlayerIndex(
                randomizeFirstPlayer,
                firstPlayerSeed,
                lineupRoles == null ? 0 : lineupRoles.size());
        String initialPlayerRole = roleAt(lineupRoles, initialPlayerIndex);
        JsonObject out = new JsonObject();
        out.addProperty("index", initialPlayerIndex);
        out.addProperty("role", initialPlayerRole);
        out.addProperty("team", teamForRole(initialPlayerRole));
        return out;
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

    private static Map<String, String> snapshotSeedProperties() {
        Map<String, String> values = new HashMap<>();
        for (String key : List.of(DECK_SEED_PROPERTY, FIRST_PLAYER_SEED_PROPERTY, AI_SEED_PROPERTY)) {
            values.put(key, System.getProperty(key));
        }
        return values;
    }

    private static void restoreSeedProperties(Map<String, String> values) {
        if (values == null) {
            return;
        }
        for (String key : List.of(DECK_SEED_PROPERTY, FIRST_PLAYER_SEED_PROPERTY, AI_SEED_PROPERTY)) {
            String value = values.get(key);
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }
    }

    private static JsonObject winnerFromLastAction(JsonArray players, JsonElement summaryElement) {
        if (players == null || players.isEmpty()
                || summaryElement == null || summaryElement.isJsonNull()) {
            return null;
        }
        String summary = summaryElement.getAsString();
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String lower = summary.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < players.size(); i++) {
            JsonObject player = players.get(i).getAsJsonObject();
            String displayName = player.has("displayName") && !player.get("displayName").isJsonNull()
                    ? player.get("displayName").getAsString().trim().toLowerCase(Locale.ROOT)
                    : "";
            String playerId = player.has("playerId") && !player.get("playerId").isJsonNull()
                    ? player.get("playerId").getAsString().trim().toLowerCase(Locale.ROOT)
                    : "";
            if (!displayName.isBlank() && lower.startsWith(displayName + " wins")) {
                return player;
            }
            if (!playerId.isBlank() && lower.startsWith(playerId + " wins")) {
                return player;
            }
        }
        return null;
    }

    private static int boardScore(GameStateSnapshot.PlayerPublicSummary player) {
        int progress = 0;
        for (com.monopoly.dto.PropertyColorProgress colorProgress : player.getPropertyColorProgress()) {
            progress += Math.min(
                    Math.max(0, colorProgress.getEffectiveCount()),
                    Math.max(1, colorProgress.getNeed()));
        }
        return player.getCompletePropertySets() * 1000
                + progress * 80
                + player.getPropertyCount() * 20
                + player.getBankTotalValueM() * 10
                + player.getHandCount();
    }

    private static int nearCompleteColors(GameStateSnapshot.PlayerPublicSummary player) {
        int count = 0;
        for (com.monopoly.dto.PropertyColorProgress progress : player.getPropertyColorProgress()) {
            int need = Math.max(1, progress.getNeed());
            int effective = Math.max(0, progress.getEffectiveCount());
            if (effective == need - 1) {
                count++;
            }
        }
        return count;
    }

    private static int maxMissingToComplete(GameStateSnapshot.PlayerPublicSummary player) {
        int best = Integer.MAX_VALUE;
        for (com.monopoly.dto.PropertyColorProgress progress : player.getPropertyColorProgress()) {
            int need = Math.max(1, progress.getNeed());
            int effective = Math.max(0, progress.getEffectiveCount());
            best = Math.min(best, Math.max(0, need - effective));
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    private static JsonObject summary(JsonArray gameReports) {
        Map<String, Integer> leaderCounts = new LinkedHashMap<>();
        Map<String, Integer> naturalWinnerCounts = new LinkedHashMap<>();
        int completed = 0;
        int naturalWinners = 0;
        int naturalWinnerAmbiguous = 0;
        for (int i = 0; i < gameReports.size(); i++) {
            JsonObject game = gameReports.get(i).getAsJsonObject();
            if (game.has("gameOver") && game.get("gameOver").getAsBoolean()) {
                completed++;
            }
            String leaderTeam = game.has("leaderTeam") && !game.get("leaderTeam").isJsonNull()
                    ? game.get("leaderTeam").getAsString() : "unknown";
            leaderCounts.put(leaderTeam, leaderCounts.getOrDefault(leaderTeam, 0) + 1);
            if (game.has("naturalWinnerTeam")
                    && !game.get("naturalWinnerTeam").isJsonNull()) {
                naturalWinners++;
                String team = game.get("naturalWinnerTeam").getAsString();
                naturalWinnerCounts.put(team, naturalWinnerCounts.getOrDefault(team, 0) + 1);
            } else if (game.has("gameOver")
                    && game.get("gameOver").getAsBoolean()
                    && (!game.has("forceEndReason") || game.get("forceEndReason").isJsonNull())) {
                naturalWinnerAmbiguous++;
            }
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("gamesWithGameOver", completed);
        summary.addProperty("gamesWithUniqueNaturalWinner", naturalWinners);
        summary.addProperty("naturalWinnerAmbiguousGames", naturalWinnerAmbiguous);
        JsonObject leaders = new JsonObject();
        for (Map.Entry<String, Integer> entry : leaderCounts.entrySet()) {
            leaders.addProperty(entry.getKey(), entry.getValue());
        }
        summary.add("leaderTeamCounts", leaders);
        JsonObject natural = new JsonObject();
        for (Map.Entry<String, Integer> entry : naturalWinnerCounts.entrySet()) {
            natural.addProperty(entry.getKey(), entry.getValue());
        }
        summary.add("naturalWinnerTeamCounts", natural);
        return summary;
    }

    private static JsonObject strategyProperties() {
        JsonObject out = new JsonObject();
        List<String> names = new ArrayList<>(System.getProperties().stringPropertyNames());
        names.sort(String::compareTo);
        for (String name : names) {
            if (name.startsWith("monopoly.search.")
                    || name.startsWith("monopoly.localRanker.")
                    || name.startsWith("monopoly.ai.")
                    || name.startsWith("monopoly.mixedBattle.")) {
                out.addProperty(name, System.getProperty(name));
            }
        }
        return out;
    }

    private static String teamFor(GameStateSnapshot.PlayerPublicSummary player) {
        String name = player.getDisplayName() == null ? "" : player.getDisplayName().toLowerCase(Locale.ROOT);
        if (name.contains("deepseek")) {
            return "deepseek";
        }
        if (name.contains("openai") || name.contains("gpt")) {
            return "openai";
        }
        if (name.contains("lookahead") || name.contains("search") || name.contains("strong")) {
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

    private static String teamForRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "deepseek", "deep_seek", "llm" -> "deepseek";
            case "lookahead", "search", "local_strong", "strong" -> "lookahead";
            case "hard", "ai_hard" -> "hard";
            case "normal", "medium", "ai_normal", "ai_medium" -> "normal";
            case "easy", "ai_easy" -> "easy";
            default -> "human";
        };
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

    private record TraceBundle(DecisionTraceSink sink) implements AutoCloseable {
        private static TraceBundle empty() {
            return new TraceBundle(null);
        }

        @Override
        public void close() {
            if (sink == null) {
                return;
            }
            try {
                sink.close();
            } catch (Exception e) {
                throw new IllegalStateException("failed to close mixed battle trace", e);
            }
        }
    }
}
