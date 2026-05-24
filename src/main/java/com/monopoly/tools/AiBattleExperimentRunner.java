package com.monopoly.tools;

import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiBattleLogger;
import com.monopoly.pattern.strategy.DeepSeekClient;
import com.monopoly.pattern.strategy.LocalRankerAiPlayStrategy;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs an AI-vs-AI battle from the command line and writes AI_BATTLE_LOG.md.
 */
public final class AiBattleExperimentRunner {

    private AiBattleExperimentRunner() {
    }

    public static void main(String[] args) {
        int players = Integer.getInteger("monopoly.aiBattle.players", 3);
        int maxSnapshots = Integer.getInteger("monopoly.aiBattle.maxSnapshots", 240);
        String sessionPrefix = System.getProperty("monopoly.aiBattle.sessionId",
                "ai-battle-" + System.currentTimeMillis());
        Metrics metrics = new Metrics();
        AiBattleLogger.setMetricsSink(metrics);
        StartSessionRequest req = new StartSessionRequest();
        req.setPlayerCount(Math.max(2, Math.min(5, players)));
        req.setGameMode("AI_VS_AI");
        req.setRandomizeFirstPlayer(true);

        AiBattleLogger.log("Experiment",
                "start sessionPrefix=" + sessionPrefix
                        + " players=" + req.getPlayerCount()
                        + " model=" + DeepSeekClient.model()
                        + " fallbackModel=" + DeepSeekClient.fallbackModel()
                        + " strictJsonFallback=" + DeepSeekClient.preferFallbackForStrictJson()
                        + " maxSnapshots=" + maxSnapshots
                        + " enabled=" + DeepSeekClient.enabled());
        int game = 0;
        int totalSnapshots = 0;
        GameStateSnapshot snap = null;
        while (totalSnapshots < maxSnapshots) {
            game++;
            int remaining = maxSnapshots - totalSnapshots;
            SnapshotSubject subject = new SnapshotSubject(game, totalSnapshots, remaining);
            GameController controller = new GameController(subject);
            configureAiStrategy(controller);
            subject.controller = controller;
            req.setSessionId(sessionPrefix + "-g" + game);
            AiBattleLogger.log("Experiment",
                    "gameStart index=" + game
                            + " session=" + req.getSessionId()
                            + " remainingSnapshots=" + remaining);
            controller.startNewSession(req);
            snap = subject.last.get();
            totalSnapshots += subject.count;
            AiBattleLogger.log("Experiment",
                    "gameFinished index=" + game
                            + " gameSnapshots=" + subject.count
                            + " totalSnapshots=" + totalSnapshots
                            + " lastPhase=" + (snap == null ? "none" : snap.getPhase())
                            + " gameOver=" + (snap != null && snap.isGameOver()));
            if (subject.count <= 0) {
                break;
            }
        }
        AiBattleLogger.setMetricsSink(null);
        AiBattleLogger.log("Experiment",
                "finished games=" + game
                        + " snapshots=" + totalSnapshots
                        + " lastPhase=" + (snap == null ? "none" : snap.getPhase())
                        + " gameOver=" + (snap != null && snap.isGameOver())
                        + " recommendation=inspect repeated fallbacks, invalid candidates, token usage, and long response waits.");
        AiBattleLogger.log("Experiment",
                metrics.summary(totalSnapshots, game));
    }

    private static void configureAiStrategy(GameController controller) {
        String strategy = System.getProperty("monopoly.aiBattle.strategy", "deepseek")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!"local_linear".equals(strategy) && !"local_ranker".equals(strategy)) {
            return;
        }
        String path = System.getProperty("monopoly.localRanker.modelPath", "").trim();
        if (path.isBlank()) {
            throw new IllegalArgumentException(
                    "monopoly.localRanker.modelPath is required when monopoly.aiBattle.strategy=local_linear");
        }
        controller.setLlmAiStrategyFactory(
                ignored -> new LocalRankerAiPlayStrategy(Path.of(path)));
    }

    private static final class SnapshotSubject implements GameUpdateSubject {
        private final AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
        private final int gameIndex;
        private final int snapshotOffset;
        private final int maxSnapshotsForGame;
        private GameController controller;
        private int count;
        private boolean gameOverLogged;
        private boolean exhaustedStateLogged;

        private SnapshotSubject(int gameIndex, int snapshotOffset, int maxSnapshotsForGame) {
            this.gameIndex = gameIndex;
            this.snapshotOffset = Math.max(0, snapshotOffset);
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
            if (gameOverLogged && snapshot != null && snapshot.isGameOver()) {
                return;
            }
            last.set(snapshot);
            count++;
            if (snapshot != null) {
                if (snapshot.isGameOver()) {
                    gameOverLogged = true;
                }
                AiBattleLogger.log("Snapshot",
                        "game=" + gameIndex
                                + " n=" + count
                                + " totalN=" + (snapshotOffset + count)
                                + " phase=" + snapshot.getPhase()
                                + " turn=" + snapshot.getCurrentPlayerId()
                                + " summary=" + snapshot.getLastActionSummary());
                if (!snapshot.isGameOver()
                        && !exhaustedStateLogged
                        && isExhaustedState(snapshot)
                        && controller != null) {
                    exhaustedStateLogged = true;
                    AiBattleLogger.log("Experiment",
                            "game=" + gameIndex
                                    + " earlyStop reason=NO_DRAWABLE_OR_PLAYABLE_CARDS"
                                    + " drawPile=" + snapshot.getDrawPileCount()
                                    + " totalHands=" + totalHandCount(snapshot));
                    controller.forceEndSession("AI_BATTLE_EXHAUSTED_STATE");
                    return;
                }
                if (!snapshot.isGameOver() && count >= maxSnapshotsForGame && controller != null) {
                    controller.forceEndSession("AI_BATTLE_TURN_LIMIT");
                }
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
        private int deepSeekUsageCalls;
        private int v4UsageCalls;
        private int fallbackUsageCalls;
        private int preferredCircuitSkips;
        private int skippedResponseCalls;
        private int malformedDecisions;
        private int invalidCandidates;
        private int decisions;
        private int actionFailures;
        private int prunedEvents;
        private int prunedFromTotal;
        private int prunedToTotal;
        private int totalPromptTokens;
        private int totalCompletionTokens;
        private int maxPromptTokens;
        private int earlyStops;

        @Override
        public void onLog(String category, String message) {
            if ("Snapshot".equals(category)) {
                if (message != null && message.contains("phase=ACTION_FAILED")) {
                    actionFailures++;
                }
                return;
            }
            if ("Experiment".equals(category) && message != null && message.contains("earlyStop reason=")) {
                earlyStops++;
                return;
            }
            if ("Decision".equals(category)) {
                decisions++;
                return;
            }
            if (!"DeepSeek".equals(category) || message == null) {
                return;
            }
            if (message.startsWith("usage model=")) {
                deepSeekUsageCalls++;
                if (message.startsWith("usage model=deepseek-v4-flash")) {
                    v4UsageCalls++;
                } else if (message.startsWith("usage model=" + DeepSeekClient.fallbackModel())) {
                    fallbackUsageCalls++;
                }
                int promptTokens = intAfter(message, "\"prompt_tokens\":");
                totalPromptTokens += promptTokens;
                maxPromptTokens = Math.max(maxPromptTokens, promptTokens);
                totalCompletionTokens += intAfter(message, "\"completion_tokens\":");
                return;
            }
            if (message.startsWith("pruned candidates ")) {
                int from = intAfter(message, "pruned candidates ");
                int to = intAfter(message, "-> ");
                prunedEvents++;
                prunedFromTotal += from;
                prunedToTotal += to;
                return;
            }
            if (message.contains("preferred JSON circuit open")) {
                preferredCircuitSkips++;
                return;
            }
            if (message.contains("strict JSON configured for fallback")) {
                preferredCircuitSkips++;
                return;
            }
            if (message.contains("skipped response model")) {
                skippedResponseCalls++;
                return;
            }
            if (message.contains("malformed decision")) {
                malformedDecisions++;
                return;
            }
            if (message.contains("invalid model candidate")) {
                invalidCandidates++;
            }
        }

        private String summary(int snapshots, int games) {
            return "metrics snapshots=" + snapshots
                    + " games=" + games
                    + " decisions=" + decisions
                    + " deepSeekCalls=" + deepSeekUsageCalls
                    + " v4Calls=" + v4UsageCalls
                    + " fallbackCalls=" + fallbackUsageCalls
                    + " circuitSkips=" + preferredCircuitSkips
                    + " skippedResponseCalls=" + skippedResponseCalls
                    + " malformedDecisions=" + malformedDecisions
                    + " invalidCandidates=" + invalidCandidates
                    + " actionFailures=" + actionFailures
                    + " earlyStops=" + earlyStops
                    + " prunedEvents=" + prunedEvents
                    + " prunedFrom=" + prunedFromTotal
                    + " prunedTo=" + prunedToTotal
                    + " promptTokens=" + totalPromptTokens
                    + " completionTokens=" + totalCompletionTokens
                    + " maxPromptTokens=" + maxPromptTokens;
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
