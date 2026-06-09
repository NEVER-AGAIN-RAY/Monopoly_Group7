package com.monopoly.controller;

import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.strategy.AiBattleLogger;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.strategy.DeepSeekAiPlayStrategy;
import com.monopoly.pattern.strategy.EasyAiPlayStrategy;
import com.monopoly.pattern.strategy.HardAiPlayStrategy;
import com.monopoly.pattern.strategy.LocalRankerAiPlayStrategy;
import com.monopoly.pattern.strategy.NormalAiPlayStrategy;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SeatAssembler {

    private static final Logger LOG = Logger.getLogger(SeatAssembler.class.getName());
    private static final List<String> DEFAULT_STUDENT_MODEL_PATHS = List.of(
            "backend/models/distillation/lookahead-student-v13-actiongateA-dagger3125-w3-listwise-mlp/candidate_ranker_mlp.json",
            "models/distillation/lookahead-student-v13-actiongateA-dagger3125-w3-listwise-mlp/candidate_ranker_mlp.json",
            "backend/models/distillation/lookahead-student-v9-dagger1956-balanced-softscore-listwise-mlp/candidate_ranker_mlp.json",
            "models/distillation/lookahead-student-v9-dagger1956-balanced-softscore-listwise-mlp/candidate_ranker_mlp.json",
            "backend/models/distillation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-mlp/candidate_ranker_mlp.json",
            "models/distillation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-mlp/candidate_ranker_mlp.json",
            "backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/candidate_ranker_mlp.json",
            "models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/candidate_ranker_mlp.json"
    );

    private IntFunction<AiPlayStrategy> llmAiStrategyFactory =
            ignored -> new DeepSeekAiPlayStrategy();
    private IntFunction<AiPlayStrategy> lookaheadAiStrategyFactory =
            ignored -> new SearchLookaheadAiPlayStrategy();

    void setLlmAiStrategyFactory(IntFunction<AiPlayStrategy> llmAiStrategyFactory) {
        this.llmAiStrategyFactory = llmAiStrategyFactory == null
                ? ignored -> new DeepSeekAiPlayStrategy()
                : llmAiStrategyFactory;
    }

    void setLookaheadAiStrategyFactory(IntFunction<AiPlayStrategy> lookaheadAiStrategyFactory) {
        this.lookaheadAiStrategyFactory = lookaheadAiStrategyFactory == null
                ? ignored -> new SearchLookaheadAiPlayStrategy()
                : lookaheadAiStrategyFactory;
    }

    List<Player> buildSeats(
            String mode,
            int count,
            List<String> customRoles,
            StartSessionRequest req,
            String currentSessionId) {
        List<Player> players = new ArrayList<>();
        if ("HVM".equals(mode)) {
            String diff = normalizeAiDifficulty(req.getAiDifficulty());
            String diffLabel = formatAiDifficultyLabel(diff);
            players.add(new HumanPlayer("human-1", "Human"));
            for (int i = 1; i < count; i++) {
                players.add(
                        new AIPlayer("ai-" + i, "AI-" + diffLabel + "-" + i, resolveAiStrategy(diff)));
            }
        } else if ("PVP".equals(mode)) {
            for (int i = 1; i <= count; i++) {
                players.add(new HumanPlayer("pvp-" + i, "Player-" + i));
            }
        } else if ("DEMO_PVP".equals(mode)) {
            for (int i = 1; i <= count; i++) {
                players.add(new HumanPlayer("human-" + i, "Player-" + i));
            }
        } else if ("LLM".equals(mode)) {
            players.add(new HumanPlayer("human-1", "Human"));
            String llmLabel = llmProviderLabel();
            for (int i = 1; i < count; i++) {
                players.add(new AIPlayer("ai-" + i, llmLabel + "-AI-" + i, createLlmAiStrategy(i)));
            }
            AiBattleLogger.log("Session", "Started LLM mode session=" + currentSessionId
                    + " players=" + count + " model=" + com.monopoly.pattern.strategy.DeepSeekClient.model());
        } else if ("AI_VS_AI".equals(mode)) {
            String llmLabel = llmProviderLabel();
            for (int i = 1; i <= count; i++) {
                players.add(new AIPlayer("ai-" + i, llmLabel + "-AI-" + i, createLlmAiStrategy(i)));
            }
            AiBattleLogger.log("Session", "Started AI_VS_AI mode session=" + currentSessionId
                    + " players=" + count + " model=" + com.monopoly.pattern.strategy.DeepSeekClient.model());
        } else {
            for (int i = 0; i < customRoles.size(); i++) {
                String displayName = req.getDisplayNames() != null && i < req.getDisplayNames().size()
                        ? req.getDisplayNames().get(i)
                        : null;
                players.add(createCustomSeat(customRoles.get(i), i + 1, displayName));
            }
            AiBattleLogger.log("Session", "Started CUSTOM mode session=" + currentSessionId
                    + " lineup=" + String.join(",", customRoles)
                    + " model=" + com.monopoly.pattern.strategy.DeepSeekClient.model());
        }
        return players;
    }

    static AiPlayStrategy resolveAiStrategy(String normalizedDifficulty) {
        String difficulty = normalizeAiDifficulty(normalizedDifficulty);
        return switch (difficulty) {
            case "NORMAL" -> new NormalAiPlayStrategy();
            case "HARD" -> new HardAiPlayStrategy();
            case "STRONG" -> new SearchLookaheadAiPlayStrategy();
            default -> new EasyAiPlayStrategy();
        };
    }

    static String normalizeAiDifficulty(String raw) {
        String difficulty = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (difficulty) {
            case "NORMAL", "MEDIUM", "AI_NORMAL", "AI_MEDIUM" -> "NORMAL";
            case "HARD", "AI_HARD" -> "HARD";
            case "STRONG", "LOCAL_STRONG", "LOOKAHEAD", "SEARCH" -> "STRONG";
            default -> "EASY";
        };
    }

    static List<String> parseCustomRoles(StartSessionRequest req) {
        List<String> rawRoles = new ArrayList<>();
        if (req.getPlayerRoles() != null) {
            for (String role : req.getPlayerRoles()) {
                if (role != null && !role.isBlank()) {
                    rawRoles.add(role);
                }
            }
        }
        if (rawRoles.isEmpty() && req.getCustomLineup() != null && !req.getCustomLineup().isBlank()) {
            for (String token : req.getCustomLineup().split("[,;\\s]+")) {
                if (!token.isBlank()) {
                    rawRoles.add(token);
                }
            }
        }
        if (rawRoles.isEmpty()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        for (String raw : rawRoles) {
            roles.add(normalizeCustomRole(raw));
        }
        return roles;
    }

    static List<String> defaultCustomRoles(int count) {
        int safeCount = Math.max(2, Math.min(5, count));
        List<String> roles = new ArrayList<>();
        for (int i = 1; i <= safeCount; i++) {
            roles.add(i <= 2 ? "HUMAN" : "LLM");
        }
        return roles;
    }

    static String normalizeCustomRole(String raw) {
        String role = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (role) {
            case "HUMAN", "PLAYER", "PVP" -> "HUMAN";
            case "LLM", "DEEPSEEK", "DEEP_SEEK" -> "LLM";
            case "LOOKAHEAD", "SEARCH", "LOCAL_STRONG", "STRONG" -> "LOOKAHEAD";
            case "STUDENT", "LLM_STUDENT", "LOCAL_RANKER", "RANKER" -> "STUDENT";
            case "EASY", "AI_EASY" -> "EASY";
            case "NORMAL", "MEDIUM", "AI_NORMAL", "AI_MEDIUM" -> "NORMAL";
            case "HARD", "AI_HARD" -> "HARD";
            default -> throw new IllegalArgumentException(
                    "Unsupported CUSTOM seat role: " + raw
                            + ". Available: human/easy/normal/hard/llm/student/lookahead/search/local_strong/strong.");
        };
    }

    Player createCustomSeat(String role, int seatNumber, String displayName) {
        return switch (role) {
            case "HUMAN" -> new HumanPlayer("pvp-" + seatNumber, safeDisplayName(displayName, "Player-" + seatNumber));
            case "LLM" -> new AIPlayer("ai-" + seatNumber, safeDisplayName(displayName, llmProviderLabel() + "-AI-" + seatNumber),
                    createLlmAiStrategy(seatNumber));
            case "LOOKAHEAD" -> new AIPlayer("ai-" + seatNumber, safeDisplayName(displayName, "AI-Lookahead-" + seatNumber),
                    createLookaheadAiStrategy(seatNumber));
            case "STUDENT" -> new AIPlayer("ai-" + seatNumber, safeDisplayName(displayName, "AI-Student-" + seatNumber),
                    createStudentAiStrategy(seatNumber));
            case "HARD" -> new AIPlayer("ai-" + seatNumber, safeDisplayName(displayName, "AI-Hard-" + seatNumber),
                    new HardAiPlayStrategy());
            case "NORMAL" -> new AIPlayer("ai-" + seatNumber, safeDisplayName(displayName, "AI-Normal-" + seatNumber),
                    new NormalAiPlayStrategy());
            default -> new AIPlayer("ai-" + seatNumber, safeDisplayName(displayName, "AI-Easy-" + seatNumber),
                    new EasyAiPlayStrategy());
        };
    }

    private static String safeDisplayName(String value, String fallback) {
        String name = value == null ? "" : value.trim();
        return name.isBlank() ? fallback : name;
    }

    private AiPlayStrategy createLlmAiStrategy(int playerNumber) {
        AiPlayStrategy strategy = llmAiStrategyFactory.apply(playerNumber);
        return strategy == null ? new DeepSeekAiPlayStrategy() : strategy;
    }

    private AiPlayStrategy createLookaheadAiStrategy(int playerNumber) {
        AiPlayStrategy strategy = lookaheadAiStrategyFactory.apply(playerNumber);
        return strategy == null ? new SearchLookaheadAiPlayStrategy() : strategy;
    }

    private AiPlayStrategy createStudentAiStrategy(int playerNumber) {
        Path modelPath = resolveStudentModelPath();
        if (modelPath != null) {
            try {
                return new LocalRankerAiPlayStrategy(modelPath);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Failed to load local student ranker: " + modelPath, ex);
            }
        }
        return createLookaheadAiStrategy(playerNumber);
    }

    private Path resolveStudentModelPath() {
        String configured = System.getProperty("monopoly.localRanker.modelPath", "").trim();
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        for (String candidate : DEFAULT_STUDENT_MODEL_PATHS) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static String llmProviderLabel() {
        return com.monopoly.pattern.strategy.DeepSeekClient.providerLabel();
    }

    static String formatAiDifficultyLabel(String normalizedDifficulty) {
        return switch (normalizedDifficulty) {
            case "NORMAL" -> "Normal";
            case "HARD" -> "Hard";
            case "STRONG" -> "Strong";
            default -> "Easy";
        };
    }
}
