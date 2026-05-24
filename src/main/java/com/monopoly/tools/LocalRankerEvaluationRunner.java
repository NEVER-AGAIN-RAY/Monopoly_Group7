package com.monopoly.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.strategy.EasyAiPlayStrategy;
import com.monopoly.pattern.strategy.HardAiPlayStrategy;
import com.monopoly.pattern.strategy.LocalRankerAiPlayStrategy;
import com.monopoly.pattern.strategy.NormalAiPlayStrategy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic, JSON-emitting evaluation runner for a Java-loadable local ranker.
 */
public final class LocalRankerEvaluationRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LocalRankerEvaluationRunner() {
    }

    public static void main(String[] args) {
        String modelPath = System.getProperty("monopoly.localRanker.modelPath", "").trim();
        if (modelPath.isBlank()) {
            throw new IllegalArgumentException("monopoly.localRanker.modelPath is required");
        }
        int games = Integer.getInteger("monopoly.localRankerEval.games", 20);
        int players = Integer.getInteger("monopoly.localRankerEval.players", 3);
        int maxSnapshots = Integer.getInteger("monopoly.localRankerEval.maxSnapshots", 240);
        long timeoutSeconds = Long.getLong("monopoly.localRankerEval.timeoutSeconds", 30L);
        int rankerSeat = Integer.getInteger("monopoly.localRankerEval.rankerSeat", 1);
        String opponentStrategy = System.getProperty("monopoly.localRankerEval.opponentStrategy", "ranker")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        String prefix = System.getProperty(
                "monopoly.localRankerEval.sessionPrefix",
                "local-ranker-eval-" + System.currentTimeMillis());

        PrintStream jsonOut = System.out;
        PrintStream suppressedOut = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        Map<String, Object> root;
        System.setOut(suppressedOut);
        try {
            List<GameSummary> gameSummaries = new ArrayList<>();
            Map<String, Integer> winsByPlayerIndex = new LinkedHashMap<>();
            Map<String, Integer> endReasons = new LinkedHashMap<>();
            int totalSnapshots = 0;
            int completedGames = 0;
            int rankerWins = 0;
            int rankerPositionSum = 0;
            int rankerLeadingGames = 0;
            int rankerTiedLeadingGames = 0;
            for (int i = 1; i <= Math.max(1, games); i++) {
                GameSummary summary = runGame(
                        prefix + "-g" + i,
                        i,
                        players,
                        maxSnapshots,
                        timeoutSeconds,
                        Path.of(modelPath),
                        rankerSeat,
                        opponentStrategy);
                gameSummaries.add(summary);
                totalSnapshots += summary.snapshots();
                if (summary.naturalWin()) {
                    completedGames++;
                    winsByPlayerIndex.merge(summary.winnerPlayerId(), 1, Integer::sum);
                }
                if (summary.rankerWon()) {
                    rankerWins++;
                }
                rankerPositionSum += summary.rankerBoardRank();
                if (summary.rankerBoardRank() == 1 && summary.rankerBoardTiedForLead()) {
                    rankerTiedLeadingGames++;
                } else if (summary.rankerBoardRank() == 1) {
                    rankerLeadingGames++;
                }
                endReasons.merge(summary.endReason(), 1, Integer::sum);
            }

            root = new LinkedHashMap<>();
            root.put("modelPath", modelPath);
            root.put("gamesRequested", Math.max(1, games));
            root.put("players", clampPlayers(players));
            root.put("maxSnapshotsPerGame", Math.max(1, maxSnapshots));
            root.put("timeoutSecondsPerGame", Math.max(1L, timeoutSeconds));
            root.put("rankerSeat", clampSeat(rankerSeat, clampPlayers(players)));
            root.put("opponentStrategy", opponentStrategy);
            root.put("evaluatedGames", gameSummaries.size());
            root.put("completedGames", completedGames);
            root.put("naturalWinRate", gameSummaries.isEmpty()
                    ? 0d : completedGames / (double) gameSummaries.size());
            root.put("rankerWinRate", gameSummaries.isEmpty()
                    ? 0d : rankerWins / (double) gameSummaries.size());
            root.put("averageSnapshots", gameSummaries.isEmpty()
                    ? 0d : totalSnapshots / (double) gameSummaries.size());
            root.put("averageRankerBoardRank", gameSummaries.isEmpty()
                    ? 0d : rankerPositionSum / (double) gameSummaries.size());
            root.put("rankerBoardLeadRate", gameSummaries.isEmpty()
                    ? 0d : rankerLeadingGames / (double) gameSummaries.size());
            root.put("rankerBoardTiedLeadRate", gameSummaries.isEmpty()
                    ? 0d : rankerTiedLeadingGames / (double) gameSummaries.size());
            root.put("winsByPlayerId", winsByPlayerIndex);
            root.put("endReasons", endReasons);
            root.put("games", gameSummaries);
        } finally {
            System.setOut(jsonOut);
            suppressedOut.close();
        }
        jsonOut.println(GSON.toJson(root));
    }

    private static GameSummary runGame(
            String sessionId,
            int gameIndex,
            int players,
            int maxSnapshots,
            long timeoutSeconds,
            Path modelPath,
            int rankerSeat,
            String opponentStrategy) {
        SnapshotSubject subject = new SnapshotSubject(Math.max(1, maxSnapshots));
        GameController controller = new GameController(subject);
        int effectivePlayers = clampPlayers(players);
        int effectiveRankerSeat = clampSeat(rankerSeat, effectivePlayers);
        controller.setLlmAiStrategyFactory(
                playerNumber -> strategyFor(playerNumber, effectiveRankerSeat, opponentStrategy, modelPath));
        subject.controller = controller;

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId(sessionId);
        req.setPlayerCount(effectivePlayers);
        req.setGameMode("AI_VS_AI");
        req.setRandomizeFirstPlayer(true);
        controller.startNewSession(req);
        boolean finished = false;
        try {
            finished = subject.done.await(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!finished && !controller.isSessionForceEndedPublic()) {
            controller.forceEndSession("LOCAL_RANKER_EVAL_TIMEOUT");
            try {
                subject.done.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        GameStateSnapshot last = subject.last.get();
        String winner = winnerPlayerId(last);
        boolean naturalWin = winner != null && last != null && last.getForceEndReason() == null;
        boolean rankerWon = naturalWin && winner.equals("ai-" + effectiveRankerSeat);
        List<PlayerScore> scores = playerScores(last);
        String rankerPlayerId = "ai-" + effectiveRankerSeat;
        int rankerScore = scoreFor(scores, rankerPlayerId);
        int rankerRank = rankFor(scores, rankerPlayerId);
        boolean rankerTiedLead = rankerRank == 1 && tiedForLead(scores);
        return new GameSummary(
                gameIndex,
                sessionId,
                subject.count,
                last == null ? "NO_SNAPSHOT" : nullTo(last.getPhase(), "UNKNOWN"),
                last == null ? "NO_SNAPSHOT" : nullTo(last.getForceEndReason(), "NATURAL_OR_LIMIT"),
                winner,
                naturalWin,
                rankerWon,
                rankerScore,
                rankerRank,
                rankerTiedLead,
                scores);
    }

    private static int clampPlayers(int players) {
        return Math.max(2, Math.min(5, players));
    }

    private static int clampSeat(int rankerSeat, int players) {
        return Math.max(1, Math.min(clampPlayers(players), rankerSeat));
    }

    private static AiPlayStrategy strategyFor(
            int playerNumber,
            int rankerSeat,
            String opponentStrategy,
            Path modelPath) {
        if (playerNumber == rankerSeat || "ranker".equals(opponentStrategy)) {
            return new LocalRankerAiPlayStrategy(modelPath);
        }
        return switch (opponentStrategy) {
            case "easy" -> new EasyAiPlayStrategy();
            case "normal" -> new NormalAiPlayStrategy();
            case "hard" -> new HardAiPlayStrategy();
            default -> throw new IllegalArgumentException(
                    "Unsupported monopoly.localRankerEval.opponentStrategy: " + opponentStrategy);
        };
    }

    private static String winnerPlayerId(GameStateSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        String winner = null;
        int winners = 0;
        int bestSets = -1;
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            if (player.getCompletePropertySets() > bestSets) {
                bestSets = player.getCompletePropertySets();
            }
            if (player.getCompletePropertySets() >= 3) {
                winner = player.getPlayerId();
                winners++;
            }
        }
        return winners == 1 && bestSets >= 3 ? winner : null;
    }

    private static String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<PlayerScore> playerScores(GameStateSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        List<PlayerScore> scores = new ArrayList<>();
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            int propertyProgress = 0;
            for (com.monopoly.dto.PropertyColorProgress progress : player.getPropertyColorProgress()) {
                propertyProgress += Math.min(progress.getEffectiveCount(), progress.getNeed());
            }
            int score = player.getCompletePropertySets() * 1000
                    + propertyProgress * 80
                    + player.getPropertyCount() * 20
                    + player.getBankTotalValueM() * 10
                    + player.getHandCount();
            scores.add(new PlayerScore(
                    player.getPlayerId(),
                    player.getDisplayName(),
                    score,
                    player.getCompletePropertySets(),
                    player.getPropertyCount(),
                    player.getBankTotalValueM(),
                    player.getHandCount()));
        }
        scores.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return scores;
    }

    private static int scoreFor(List<PlayerScore> scores, String playerId) {
        for (PlayerScore score : scores) {
            if (score.playerId().equals(playerId)) {
                return score.score();
            }
        }
        return 0;
    }

    private static int rankFor(List<PlayerScore> scores, String playerId) {
        int rank = 1;
        int previousScore = Integer.MIN_VALUE;
        int seen = 0;
        for (PlayerScore score : scores) {
            seen++;
            if (score.score() != previousScore) {
                rank = seen;
                previousScore = score.score();
            }
            if (score.playerId().equals(playerId)) {
                return rank;
            }
        }
        return scores.isEmpty() ? 0 : scores.size() + 1;
    }

    private static boolean tiedForLead(List<PlayerScore> scores) {
        return scores.size() > 1 && scores.get(0).score() == scores.get(1).score();
    }

    private record GameSummary(
            int gameIndex,
            String sessionId,
            int snapshots,
            String finalPhase,
            String endReason,
            String winnerPlayerId,
            boolean naturalWin,
            boolean rankerWon,
            int rankerBoardScore,
            int rankerBoardRank,
            boolean rankerBoardTiedForLead,
            List<PlayerScore> boardScores) {
    }

    private record PlayerScore(
            String playerId,
            String displayName,
            int score,
            int completeSets,
            int propertyCount,
            int bankValueM,
            int handCount) {
    }

    private static final class SnapshotSubject implements GameUpdateSubject {
        private final AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final int maxSnapshots;
        private GameController controller;
        private int count;
        private boolean gameOverLogged;
        private boolean forced;

        private SnapshotSubject(int maxSnapshots) {
            this.maxSnapshots = Math.max(1, maxSnapshots);
        }

        @Override
        public void registerObserver(GameUpdateObserver observer) {
        }

        @Override
        public void unregisterObserver(GameUpdateObserver observer) {
        }

        @Override
        public void notifyStateChanged(GameStateSnapshot snapshot) {
            if (gameOverLogged && snapshot != null && snapshot.isGameOver()) {
                return;
            }
            last.set(snapshot);
            count++;
            if (snapshot == null) {
                return;
            }
            if (snapshot.isGameOver()) {
                gameOverLogged = true;
                done.countDown();
                return;
            }
            if (!forced && isExhaustedState(snapshot) && controller != null) {
                forced = true;
                controller.forceEndSession("LOCAL_RANKER_EVAL_EXHAUSTED_STATE");
                return;
            }
            if (!forced && count >= maxSnapshots && controller != null) {
                forced = true;
                controller.forceEndSession("LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT");
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
}
