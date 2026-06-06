package com.monopoly.controller;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.dto.PropertyColorProgress;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.core.GameConstants;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.settlement.PropertyZoneSummary;
import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.card.ActionCard;
import com.monopoly.pattern.factory.CardFactory;
import com.monopoly.pattern.factory.MonopolyDealCardFactory;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.strategy.AiBattleLogger;
import com.monopoly.pattern.strategy.DeepSeekAiPlayStrategy;
import com.monopoly.pattern.strategy.EasyAiPlayStrategy;
import com.monopoly.pattern.strategy.HardAiPlayStrategy;
import com.monopoly.pattern.strategy.LocalRankerAiPlayStrategy;
import com.monopoly.pattern.strategy.NormalAiPlayStrategy;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import com.monopoly.presentation.HandCardJson;
import com.monopoly.persistence.GameSessionMemento;

import static com.monopoly.controller.ProtocolErrors.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * [Facade]
 * Single entry point for the JavaFX client and WebSocket layer; hides turn flow and effect-stack details.
 * <p>
 * Core logic is split into six services:
 * <ul>
 *   <li>TurnFlowService — turn flow (draw, play, discard, end turn, action cards)</li>
 *   <li>EffectStackOrchestrator — effect stack and response timers</li>
 *   <li>AiTurnService — AI turn execution</li>
 *   <li>RentSettlementService — rent settlement</li>
 *   <li>PauseVoteService — pause and PVP voting</li>
 *   <li>SaveLoadService — save, load, autosave</li>
 * </ul>
 */
public class GameController implements AiGameBridge {

    private static final Logger LOG = Logger.getLogger(GameController.class.getName());
    private static final boolean VERIFY_DECK = Boolean.parseBoolean(
            System.getProperty("monopoly.verifyDeck", "false"));
    private static final boolean TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO =
            Boolean.parseBoolean(System.getProperty(
                    "monopoly.search.trace.includeMemento",
                    "false"));
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

    private final GameEngineSingleton engine;
    private final TurnManager turnManager = new TurnManager();
    private final CardFactory cardFactory = new MonopolyDealCardFactory();
    private final GameUpdateSubject gameUpdateSubject;

    /* --- six services --- */
    private final TurnFlowService turnFlowService;
    private final EffectStackOrchestrator effectStackOrchestrator;
    private final AiTurnService aiTurnService;
    private final SaveLoadService saveLoadService;
    private final PauseVoteService pauseVoteService;
    private final RentSettlementService rentSettlementService;

    /* --- session state --- */
    private final GameContext gameContext = new GameContext();
    private final List<Player> sessionPlayers = new ArrayList<>();
    private String currentSessionId = "unknown";
    private String lastErrorCode;
    private String lastErrorMessage;
    private long lastErrorTimestampEpochMs;
    private long sessionStartEpochMs;
    private volatile boolean sessionForceEnded;
    private volatile boolean sessionEndedNaturally;
    private String forceEndReason;
    private final Set<String> quitPlayerIds = new HashSet<>();
    private String sessionGameMode = "HVM";
    private int fullRoundsCompleted;
    private long playEventSequence;
    private long stateSequence;
    private volatile boolean suppressAiAutoContinuation;
    private IntFunction<AiPlayStrategy> llmAiStrategyFactory =
            ignored -> new DeepSeekAiPlayStrategy();
    private IntFunction<AiPlayStrategy> lookaheadAiStrategyFactory =
            ignored -> new SearchLookaheadAiPlayStrategy();

    // --- constructor ---

    public GameController(GameUpdateSubject gameUpdateSubject) {
        this(gameUpdateSubject, GameEngineSingleton.createIsolated());
    }

    public GameController(GameUpdateSubject gameUpdateSubject, GameEngineSingleton engine) {
        this.gameUpdateSubject = gameUpdateSubject;
        this.engine = engine != null ? engine : GameEngineSingleton.createIsolated();
        this.turnFlowService = new TurnFlowService(this);
        this.effectStackOrchestrator = new EffectStackOrchestrator(this, turnFlowService);
        this.turnFlowService.wireEffectStack(effectStackOrchestrator);
        this.aiTurnService = new AiTurnService(this, turnFlowService);
        this.saveLoadService = new SaveLoadService(this);
        this.pauseVoteService = new PauseVoteService(this);
        this.rentSettlementService = new RentSettlementService(this);
        clearLastError();
    }

    /**
     * Test/simulation hook. Normal WebSocket sessions keep using DeepSeekAiPlayStrategy.
     */
    public void setLlmAiStrategyFactory(IntFunction<AiPlayStrategy> llmAiStrategyFactory) {
        this.llmAiStrategyFactory = llmAiStrategyFactory == null
                ? ignored -> new DeepSeekAiPlayStrategy()
                : llmAiStrategyFactory;
    }

    public void setLookaheadAiStrategyFactory(IntFunction<AiPlayStrategy> lookaheadAiStrategyFactory) {
        this.lookaheadAiStrategyFactory = lookaheadAiStrategyFactory == null
                ? ignored -> new SearchLookaheadAiPlayStrategy()
                : lookaheadAiStrategyFactory;
    }

    // ═══════════════════════════════════════════════════════
    //  Session lifecycle
    // ═══════════════════════════════════════════════════════

    public void startNewSession(String sessionId) {
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId(sessionId);
        req.setPlayerCount(2);
        req.setGameMode("HVM");
        req.setAiDifficulty("EASY");
        req.setRandomizeFirstPlayer(false);
        startNewSession(req);
    }

    /**
     * Starts a session: builds the standard deck, shuffles it into the draw pile,
     * and deals opening hands. Loading restores deck order via GameSessionMemento.
     */
    public void startNewSession(StartSessionRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("StartSessionRequest 不能为 null。");
        }
        if (pauseVoteService.isPaused()) {
            recordError("PAUSED", "游戏已暂停，无法开始或重开对局。");
            pushSnapshot(currentSessionId, "RULE_VIOLATION");
            return;
        }
        String mode = req.getGameMode() == null ? "" : req.getGameMode().trim().toUpperCase();
        if (mode.isBlank()) {
            mode = "HVM";
        }
        int requestedCount = req.getPlayerCount() <= 0 ? 2 : req.getPlayerCount();
        List<String> customRoles = "CUSTOM".equals(mode)
                ? parseCustomRoles(req)
                : List.of();
        int count = customRoles.isEmpty() ? requestedCount : customRoles.size();
        if (count < 2 || count > 5) {
            throw new IllegalArgumentException("playerCount 必须在 2–5 之间，当前为 " + count + "。");
        }
        if ("CUSTOM".equals(mode) && customRoles.isEmpty()) {
            customRoles = defaultCustomRoles(count);
        }
        if (!"HVM".equals(mode) && !"PVP".equals(mode)
                && !"LLM".equals(mode) && !"AI_VS_AI".equals(mode)
                && !"CUSTOM".equals(mode)) {
            throw new IllegalArgumentException(
                    "gameMode 必须为 HVM、PVP、LLM、AI_VS_AI 或 CUSTOM，当前为 " + req.getGameMode() + "。");
        }

        String sid = req.getSessionId();
        currentSessionId = (sid == null || sid.isBlank()) ? "session-default" : sid;
        sessionGameMode = mode;
        pauseVoteService.reset();
        fullRoundsCompleted = 0;
        sessionStartEpochMs = System.currentTimeMillis();
        sessionForceEnded = false;
        sessionEndedNaturally = false;
        suppressAiAutoContinuation = false;
        forceEndReason = null;
        quitPlayerIds.clear();
        playEventSequence = 0L;
        stateSequence = 0L;
        List<Card> deck = new ArrayList<>(cardFactory.createStandardDeck108());
        Long deckSeed = Long.getLong("monopoly.deck.seed");
        if (deckSeed != null) {
            Collections.shuffle(deck, new Random(deckSeed));
            engine.useDeterministicReshuffleSeed(reshuffleSeed(deckSeed));
        } else {
            Collections.shuffle(deck, ThreadLocalRandom.current());
            engine.clearDeterministicReshuffleSeed();
        }
        engine.attachDrawPile(deck);

        sessionPlayers.clear();
        if ("HVM".equals(mode)) {
            String diff = normalizeAiDifficulty(req.getAiDifficulty());
            String diffLabel = formatAiDifficultyLabel(diff);
            sessionPlayers.add(new HumanPlayer("human-1", "Human"));
            for (int i = 1; i < count; i++) {
                sessionPlayers.add(
                        new AIPlayer("ai-" + i, "AI-" + diffLabel + "-" + i, resolveAiStrategy(diff)));
            }
        } else if ("PVP".equals(mode)) {
            for (int i = 1; i <= count; i++) {
                sessionPlayers.add(new HumanPlayer("pvp-" + i, "Player-" + i));
            }
        } else if ("LLM".equals(mode)) {
            sessionPlayers.add(new HumanPlayer("human-1", "Human"));
            String llmLabel = llmProviderLabel();
            for (int i = 1; i < count; i++) {
                sessionPlayers.add(new AIPlayer("ai-" + i, llmLabel + "-AI-" + i, createLlmAiStrategy(i)));
            }
            AiBattleLogger.log("Session", "Started LLM mode session=" + currentSessionId
                    + " players=" + count + " model=" + com.monopoly.pattern.strategy.DeepSeekClient.model());
        } else if ("AI_VS_AI".equals(mode)) {
            String llmLabel = llmProviderLabel();
            for (int i = 1; i <= count; i++) {
                sessionPlayers.add(new AIPlayer("ai-" + i, llmLabel + "-AI-" + i, createLlmAiStrategy(i)));
            }
            AiBattleLogger.log("Session", "Started AI_VS_AI mode session=" + currentSessionId
                    + " players=" + count + " model=" + com.monopoly.pattern.strategy.DeepSeekClient.model());
        } else {
            for (int i = 0; i < customRoles.size(); i++) {
                String displayName = req.getDisplayNames() != null && i < req.getDisplayNames().size()
                        ? req.getDisplayNames().get(i)
                        : null;
                sessionPlayers.add(createCustomSeat(customRoles.get(i), i + 1, displayName));
            }
            AiBattleLogger.log("Session", "Started CUSTOM mode session=" + currentSessionId
                    + " lineup=" + String.join(",", customRoles)
                    + " model=" + com.monopoly.pattern.strategy.DeepSeekClient.model());
        }

        turnManager.bindTurnOrder(sessionPlayers);
        gameContext.bindPlayers(sessionPlayers);
        gameContext.clearEffectStack();
        gameContext.clearPendingDoubleRent();
        gameContext.resetAiHistory();
        effectStackOrchestrator.cancelPendingResponseTimeout();

        int initialEach = TurnFlowService.INITIAL_HAND_SIZE;
        dealInitialHands:
        for (int round = 0; round < initialEach; round++) {
            for (Player p : sessionPlayers) {
                Card card = engine.drawOne();
                if (card == null) {
                    break dealInitialHands;
                }
                p.receiveCardToHand(card);
            }
        }

        if (req.isRandomizeFirstPlayer()) {
            Long firstPlayerSeed = Long.getLong("monopoly.firstPlayer.seed");
            int firstIndex = firstPlayerSeed != null
                    ? new Random(firstPlayerSeed).nextInt(sessionPlayers.size())
                    : ThreadLocalRandom.current().nextInt(sessionPlayers.size());
            turnManager.setCurrentIndex(firstIndex);
        }

        Player current = turnManager.getCurrentPlayer();
        turnFlowService.initForSession(current);
        clearLastError();
        assertDeckIntegrityOrLog();
        pushSnapshot(currentSessionId, "INIT", "新局已开始：牌堆已随机洗牌，起手按真人发牌方式轮流发 5 张。");
        if (!suppressAiAutoContinuation && current instanceof AIPlayer ai) {
            aiTurnService.executeAiTurn(ai);
        }
    }

    private static AiPlayStrategy resolveAiStrategy(String normalizedDifficulty) {
        String difficulty = normalizeAiDifficulty(normalizedDifficulty);
        return switch (difficulty) {
            case "NORMAL" -> new NormalAiPlayStrategy();
            case "HARD" -> new HardAiPlayStrategy();
            case "STRONG" -> new SearchLookaheadAiPlayStrategy();
            default -> new EasyAiPlayStrategy();
        };
    }

    private static String normalizeAiDifficulty(String raw) {
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

    private static long reshuffleSeed(long deckSeed) {
        return deckSeed ^ 0x9E3779B97F4A7C15L;
    }

    private static List<String> parseCustomRoles(StartSessionRequest req) {
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

    private static List<String> defaultCustomRoles(int count) {
        int safeCount = Math.max(2, Math.min(5, count));
        List<String> roles = new ArrayList<>();
        for (int i = 1; i <= safeCount; i++) {
            roles.add(i <= 2 ? "HUMAN" : "LLM");
        }
        return roles;
    }

    private static String normalizeCustomRole(String raw) {
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
                    "CUSTOM 席位角色不支持: " + raw
                            + "。可用 human/easy/normal/hard/llm/student/lookahead/search/local_strong/strong。");
        };
    }

    private Player createCustomSeat(String role, int seatNumber, String displayName) {
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

    private static String formatAiDifficultyLabel(String normalizedDifficulty) {
        return switch (normalizedDifficulty) {
            case "NORMAL" -> "Normal";
            case "HARD" -> "Hard";
            case "STRONG" -> "Strong";
            default -> "Easy";
        };
    }

    // ═══════════════════════════════════════════════════════
    //  Turn actions (TurnFlowService)
    // ═══════════════════════════════════════════════════════

    public void drawCards(Player player, int count) {
        turnFlowService.drawCards(player, count);
    }

    public void playCard(Player player, Card card, String actionType) {
        turnFlowService.playCard(player, card, actionType, null);
    }

    public void playCard(Player player, Card card, String actionType, ActionParamContext params) {
        turnFlowService.playCard(player, card, actionType, params);
    }

    public void discardFromHand(Player player, Card card) {
        turnFlowService.discardFromHand(player, card);
    }

    public void reassignWildProperty(Player player, String wildPropertyCardId, String newColorKey) {
        turnFlowService.reassignWildProperty(player, wildPropertyCardId, newColorKey);
    }

    public void handleReassignWildCommand(String wildPropertyCardId, String newColorKey) {
        turnFlowService.reassignWildProperty(requireCurrentPlayer(), wildPropertyCardId, newColorKey);
    }

    public void endTurn(Player player) {
        if (player == null) {
            return;
        }
        Player next = turnFlowService.endTurn(player);
        if (!suppressAiAutoContinuation && next instanceof AIPlayer ai) {
            aiTurnService.executeAiTurn(ai);
        }
    }

    void resumeAiTurnIfNeeded() {
        if (suppressAiAutoContinuation) {
            return;
        }
        if (turnFlowService.currentTurnPhase == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            return;
        }
        Player current = turnManager.getCurrentPlayer();
        if (current instanceof AIPlayer ai) {
            aiTurnService.continueAiTurn(ai);
        }
    }

    public ActionEffectResult handleActionCardCommand(
            int handIndex, String targetPlayerId, String colorKey,
            int targetPropIndex, int actorPropIndex) {
        return turnFlowService.handleActionCardCommand(
                handIndex, targetPlayerId, colorKey, targetPropIndex, actorPropIndex);
    }

    public ActionEffectResult handleActionCardCommand(ActionParamContext params) {
        return turnFlowService.handleActionCardCommand(params);
    }

    public ActionEffectResult playActionCard(
            Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        return turnFlowService.playActionCard(actor, card, ctx, params);
    }

    // ═══════════════════════════════════════════════════════
    //  WebSocket command handlers
    // ═══════════════════════════════════════════════════════

    public void handleDrawCommand(int count) {
        try {
            ensureNotPaused();
            ensureSessionActive();
            clearLastError();
            Player current = requireCurrentPlayer();
            drawCards(current, count);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    public void handlePlayActionRequest(PlayActionRequest req) {
        try {
            ensureNotPaused();
            ensureSessionActive();
            clearLastError();
            validatePlayActionRequest(req);
            String normalized = req.getActionType().trim().toUpperCase();

            if (turnFlowService.currentTurnPhase
                    == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
                if ("RESPONSE_PASS".equals(normalized)) {
                    effectStackOrchestrator.performResponsePass(
                            req.getActingPlayerId(), req.getPaymentCardIds());
                    return;
                }
                if ("ACTION".equals(normalized)) {
                    effectStackOrchestrator.handleWaiverPlay(req);
                    return;
                }
                throw new IllegalStateException(
                        "等待响应阶段仅允许打出免租（ACTION）或放弃（RESPONSE_PASS）。");
            }

            if ("DISCARD".equals(normalized)) {
                handleDiscardRequest(req);
                return;
            }

            Player current = requireCurrentPlayer();
            ActionParamContext params = ActionParamContext.fromPlayRequest(req);
            if ("ACTION".equals(normalized)) {
                turnFlowService.handleActionCardCommand(params);
                return;
            }
            Card card = turnFlowService.resolveCardInHand(
                    current, req.getCardId(), req.getHandIndex());
            turnFlowService.playCard(current, card, normalized, params);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    /**
     * PLAY phase: legal targets/params for an action card (client sends PLAY after picking).
     */
    public ActionOptionsResult queryActionOptionsForHandCard(String playerId, String cardId) {
        try {
            ensureNotPaused();
            ensureSessionActive();
            clearLastError();
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不能为空。");
            }
            if (cardId == null || cardId.isBlank()) {
                throw new IllegalArgumentException("cardId 不能为空。");
            }
            Player cur = requireCurrentPlayer();
            if (!playerId.trim().equals(cur.getPlayerId())) {
                throw new IllegalStateException("仅当前回合玩家可查询行动选项。");
            }
            if (turnFlowService.currentTurnPhase != TurnFlowService.TurnPhase.PLAY) {
                throw new IllegalStateException("仅在出牌阶段可查询行动选项。");
            }
            Card c = turnFlowService.resolveCardInHand(cur, cardId, null);
            if (!(c instanceof ActionCard ac)) {
                throw new IllegalArgumentException("该卡牌不是行动牌。");
            }
            return ActionOptionsService.build(
                    cur, ac, List.copyOf(sessionPlayers), engine, gameContext);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    /**
     * PLAY phase: options for DEPOSIT/DEPLOY/DISCARD/ACTION (PLAY_OPTIONS wizard).
     */
    public ActionOptionsResult queryPlayOptions(String playerId, String cardId, String actionType) {
        try {
            ensureNotPaused();
            ensureSessionActive();
            clearLastError();
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不能为空。");
            }
            if (cardId == null || cardId.isBlank()) {
                throw new IllegalArgumentException("cardId 不能为空。");
            }
            if (actionType == null || actionType.isBlank()) {
                throw new IllegalArgumentException("actionType 不能为空。");
            }
            Player cur = requireCurrentPlayer();
            if (!playerId.trim().equals(cur.getPlayerId())) {
                throw new IllegalStateException("仅当前回合玩家可查询出牌选项。");
            }
            if (turnFlowService.currentTurnPhase != TurnFlowService.TurnPhase.PLAY) {
                throw new IllegalStateException("仅在出牌阶段可查询出牌选项。");
            }
            Card c = turnFlowService.resolveCardInHand(cur, cardId, null);
            return PlayOptionsService.build(cur, c, actionType, List.copyOf(sessionPlayers), engine, gameContext);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    public void validatePlayActionRequest(PlayActionRequest req) {
        if (req == null) {
            throw new ProtocolValidationException(ERR_PLAY_REQUEST_EMPTY, "出牌请求不能为空。");
        }
        if (req.getActionType() == null || req.getActionType().isBlank()) {
            throw new ProtocolValidationException(
                    ERR_PLAY_ACTION_TYPE_REQUIRED, "actionType 不能为空。");
        }
        String normalized = req.getActionType().trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = Set.of(
                "DEPLOY", "DEPOSIT", "ACTION", "DISCARD", "RESPONSE_PASS");
        if (!allowed.contains(normalized)) {
            throw new ProtocolValidationException(
                    ERR_PLAY_ACTION_TYPE_INVALID, "不支持的 actionType: " + req.getActionType());
        }
        if ("RESPONSE_PASS".equals(normalized)) {
            if (req.getActingPlayerId() == null || req.getActingPlayerId().isBlank()) {
                throw new ProtocolValidationException(
                        ERR_PLAY_ACTING_PLAYER_REQUIRED,
                        "RESPONSE_PASS 必须提供 actingPlayerId。");
            }
            return;
        }
        if (req.getCardId() == null || req.getCardId().isBlank()) {
            if (req.getHandIndex() == null) {
                throw new ProtocolValidationException(
                        ERR_PLAY_CARD_SELECTOR_REQUIRED, "必须提供 cardId 或 handIndex。");
            }
            if (req.getHandIndex() < 0) {
                throw new ProtocolValidationException(
                        ERR_PLAY_HAND_INDEX_INVALID, "handIndex 必须 >= 0。");
            }
        }
    }

    private void handleDiscardRequest(PlayActionRequest req) {
        Player current = requireCurrentPlayer();
        Card card = turnFlowService.resolveCardInHand(
                current, req.getCardId(), req.getHandIndex());
        turnFlowService.discardFromHand(current, card);
    }

    public void handlePlayCommand(int handIndex, String actionType) {
        ensureSessionActive();
        PlayActionRequest r = new PlayActionRequest();
        r.setHandIndex(handIndex);
        r.setActionType(actionType);
        handlePlayActionRequest(r);
    }

    public void handleEndTurnCommand() {
        try {
            ensureSessionActive();
            clearLastError();
            Player current = requireCurrentPlayer();
            endTurn(current);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    public void handleQuitCommand(String playerId) {
        if (isSessionEnded()) {
            return;
        }
        if (playerId == null || playerId.isBlank()) {
            return;
        }
        if (sessionPlayers.isEmpty()) {
            return;
        }
        boolean known = false;
        for (Player p : sessionPlayers) {
            if (playerId.equals(p.getPlayerId())) {
                known = true;
                break;
            }
        }
        if (!known) {
            return;
        }
        quitPlayerIds.add(playerId);
        if (quitPlayerIds.size() >= sessionPlayers.size()) {
            sessionForceEnded = true;
            forceEndReason = "ALL_QUIT";
            pushSnapshot(currentSessionId, "GAME_FORCE_END");
        }
    }

    public void forceEndSession(String reason) {
        if (sessionForceEnded || sessionEndedNaturally) {
            return;
        }
        sessionForceEnded = true;
        forceEndReason = (reason == null || reason.isBlank()) ? "FORCE_END" : reason.trim();
        pushSnapshot(currentSessionId, "GAME_FORCE_END");
    }

    void endSessionNaturally(String summary) {
        if (sessionForceEnded || sessionEndedNaturally) {
            return;
        }
        sessionEndedNaturally = true;
        forceEndReason = null;
        pushSnapshot(currentSessionId, "GAME_OVER", summary);
    }

    public void shutdown() {
        aiTurnService.shutdown();
        effectStackOrchestrator.shutdown();
    }

    public void handleResponsePass(String actingPlayerId) {
        try {
            ensureSessionActive();
            clearLastError();
            effectStackOrchestrator.performResponsePass(actingPlayerId);
        } catch (RuntimeException e) {
            recordErrorAndSnapshot(e);
            throw e;
        }
    }

    @Override
    public void submitPlayAction(PlayActionRequest request) {
        handlePlayActionRequest(request);
    }

    // ═══════════════════════════════════════════════════════
    //  Rent settlement (RentSettlementService)
    // ═══════════════════════════════════════════════════════

    public PaymentSettlement.Result requestRentPayment(
            Player from, Player to, int amount, GameContext context) {
        return rentSettlementService.requestRentPayment(from, to, amount, context);
    }

    public PaymentSettlement.Result collectRentForColor(
            Player landlord, Player tenant, String colorKey) {
        return rentSettlementService.collectRentForColor(landlord, tenant, colorKey);
    }

    public int computeRentDueForColor(Player landlord, String colorKey) {
        return rentSettlementService.computeRentDueForColor(landlord, colorKey);
    }

    // ═══════════════════════════════════════════════════════
    //  Pause / PVP vote (PauseVoteService)
    // ═══════════════════════════════════════════════════════

    public boolean isPaused() {
        return pauseVoteService.isPaused();
    }

    public boolean isPausePending() {
        return pauseVoteService.isPausePending();
    }

    public Set<String> getPauseAcksView() {
        return pauseVoteService.getPauseAcksView();
    }

    public boolean isPvpMode() {
        return "PVP".equals(sessionGameMode);
    }

    public boolean isAiBattleMode() {
        return "LLM".equals(sessionGameMode) || "AI_VS_AI".equals(sessionGameMode);
    }

    public void setSuppressAiAutoContinuation(boolean suppressAiAutoContinuation) {
        this.suppressAiAutoContinuation = suppressAiAutoContinuation;
    }

    public void pause() {
        pauseVoteService.pause();
    }

    public void requestPause() {
        pauseVoteService.requestPause();
    }

    public void acknowledgePause(String playerId) {
        pauseVoteService.acknowledgePause(playerId);
    }

    public void resume() {
        pauseVoteService.resume();
    }

    // ═══════════════════════════════════════════════════════
    //  Save / load (SaveLoadService)
    // ═══════════════════════════════════════════════════════

    public String exportSessionJson() {
        return saveLoadService.exportSessionJson();
    }

    public void importSessionJson(String json) {
        saveLoadService.importSessionJson(json);
    }

    public void pushCurrentState(String phase, String actionSummary) {
        pushSnapshot(currentSessionId, phase, actionSummary);
    }

    // ═══════════════════════════════════════════════════════
    //  Query API
    // ═══════════════════════════════════════════════════════

    public TurnManager getTurnManager() {
        return turnManager;
    }

    public List<Player> getSessionPlayersView() {
        return Collections.unmodifiableList(sessionPlayers);
    }

    public Player getCurrentPlayer() {
        return turnManager.getCurrentPlayer();
    }

    public int getFullRoundsCompleted() {
        return fullRoundsCompleted;
    }

    public GameEngineSingleton getEngine() {
        return engine;
    }

    String getCurrentSessionId() {
        return currentSessionId;
    }

    public String getCurrentSessionIdPublic() {
        return currentSessionId;
    }

    String getCurrentTurnPlayerId() {
        return turnFlowService.currentTurnPlayerId;
    }

    Player requireCurrentPlayer() {
        Player current = turnManager.getCurrentPlayer();
        if (current == null) {
            throw new IllegalStateException("当前没有可行动玩家，请先 startNewSession。");
        }
        return current;
    }

    // ═══════════════════════════════════════════════════════
    //  Package-private hooks for services
    // ═══════════════════════════════════════════════════════

    GameContext getGameContext() {
        return gameContext;
    }

    void refreshAiDecisionContext() {
        gameContext.bindPlayers(getSessionPlayersView());
        TurnFlowService.TurnPhase phase = turnFlowService.currentTurnPhase;
        gameContext.setTurnState(
                turnFlowService.currentTurnPlayerId,
                phase == null ? "UNKNOWN" : phase.name(),
                Math.max(1, fullRoundsCompleted + 1),
                turnFlowService.currentTurnActionCount,
                TurnFlowService.MAX_ACTIONS_PER_TURN);
        gameContext.setStateSequence(stateSequence);
    }

    public GameContext refreshAndGetAiDecisionContextForSimulation() {
        refreshAiDecisionContext();
        return gameContext;
    }

    public void resolvePaymentChoiceForSimulation(String tenantId, List<String> explicitPaymentCardIds) {
        ensureSessionActive();
        effectStackOrchestrator.resolvePaymentChoiceForSimulation(tenantId, explicitPaymentCardIds);
    }

    void attachAuxiliaryDecisionMementoForTrace() {
        if (!TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO) {
            gameContext.clearAuxiliaryDecisionMementoJson();
            return;
        }
        gameContext.setAuxiliaryDecisionMementoJson(GameSessionMemento.capture(this).toJson());
    }

    Player resolvePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        for (Player p : sessionPlayers) {
            if (p.getPlayerId().equals(playerId)) {
                return p;
            }
        }
        return null;
    }

    boolean isSessionForceEnded() {
        return sessionForceEnded;
    }

    public boolean isSessionForceEndedPublic() {
        return sessionForceEnded;
    }

    boolean isSessionEnded() {
        return sessionForceEnded || sessionEndedNaturally;
    }

    public boolean isSessionEndedPublic() {
        return isSessionEnded();
    }

    void onTurnAdvanced(Player nextPlayer) {
        if (nextPlayer != null && !sessionPlayers.isEmpty()
                && sessionPlayers.get(0).getPlayerId().equals(nextPlayer.getPlayerId())) {
            fullRoundsCompleted++;
            maybeAutosaveAfterFullRound();
        }
    }

    void ensureNotPaused() {
        pauseVoteService.ensureNotPaused();
    }

    void ensureSessionActive() {
        if (sessionEndedNaturally) {
            throw new IllegalStateException("对局已结束，不可继续操作。");
        }
        if (sessionForceEnded) {
            throw new IllegalStateException("对局已强制结束，不可继续操作。");
        }
        if (sessionStartEpochMs <= 0) {
            return;
        }
        if (isAiBattleMode()) {
            return;
        }
        long elapsed = System.currentTimeMillis() - sessionStartEpochMs;
        if (elapsed <= sessionLimitMs()) {
            return;
        }
        sessionForceEnded = true;
        forceEndReason = "TIMEOUT";
        pushSnapshot(currentSessionId, "GAME_FORCE_END");
        throw new IllegalStateException("对局已超过单局时长上限，已强制结束。");
    }

    void recordError(String code, String message) {
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
        this.lastErrorTimestampEpochMs = System.currentTimeMillis();
    }

    void clearLastError() {
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
        this.lastErrorTimestampEpochMs = 0L;
    }

    void assertDeckIntegrityOrLog() {
        if (!VERIFY_DECK) {
            return;
        }
        int total = engine.countAllCardsInPlay(sessionPlayers);
        int expected = GameConstants.STANDARD_DECK_SIZE;
        if (total == expected) {
            return;
        }
        String msg = "Deck integrity violation: expected " + expected
                + " cards in play, found " + total;
        LOG.log(Level.SEVERE, msg);
        System.err.println("[DECK_VERIFY] " + msg);
        throw new IllegalStateException(msg);
    }

    /**
     * After SaveLoadService.importSessionJson: reset runtime flags and broadcast INIT.
     */
    void resetStateAfterLoad(String gameMode) {
        effectStackOrchestrator.cancelPendingResponseTimeout();
        clearLastError();
        pauseVoteService.reset();
        this.sessionGameMode = (gameMode != null && !gameMode.isBlank())
                ? gameMode.trim().toUpperCase() : "HVM";
        this.sessionEndedNaturally = false;
        this.sessionForceEnded = false;
        this.suppressAiAutoContinuation = false;
        this.forceEndReason = null;
        this.fullRoundsCompleted = 0;
        this.playEventSequence = 0L;
        this.stateSequence = 0L;
        gameContext.resetAiHistory();
        quitPlayerIds.clear();
        assertDeckIntegrityOrLog();
        pushSnapshot(currentSessionId, "INIT", "Session loaded from save.");
    }

    // --- snapshot broadcast ---

    void pushSnapshot(String sessionId, String phase) {
        pushSnapshot(sessionId, phase, null);
    }

    void pushSnapshot(String sessionId, String phase, String actionSummary) {
        pushSnapshot(sessionId, phase, actionSummary, null, null, null);
    }

    void pushSnapshot(
            String sessionId,
            String phase,
            String actionSummary,
            Player playedBy,
            Card playedCard,
            String playedActionType) {
        String originalPhase = phase;
        if (!sessionForceEnded && sessionStartEpochMs > 0 && !isAiBattleMode()) {
            long elapsed = System.currentTimeMillis() - sessionStartEpochMs;
            if (elapsed > sessionLimitMs()) {
                sessionForceEnded = true;
                forceEndReason = "TIMEOUT";
            }
        }
        if (sessionForceEnded) {
            phase = "GAME_FORCE_END";
        } else if (sessionEndedNaturally) {
            phase = "GAME_OVER";
        }

        String summary = (actionSummary != null && !actionSummary.isBlank())
                ? actionSummary
                : fallbackActionSummary(phase);

        TurnFlowService.TurnPhase tp = turnFlowService.currentTurnPhase;

        GameStateSnapshot snap = new GameStateSnapshot();
        snap.setSessionId(sessionId);
        snap.setPhase(phase);
        snap.setStateSequence(++stateSequence);
        snap.setLastActionSummary(summary);
        snap.setCurrentPlayerId(turnFlowService.currentTurnPlayerId);
        snap.setTurnPhase(tp == null ? "UNKNOWN" : tp.name());
        snap.setActionsUsedThisTurn(turnFlowService.currentTurnActionCount);
        snap.setActionsRemainingThisTurn(
                Math.max(0, TurnFlowService.MAX_ACTIONS_PER_TURN - turnFlowService.currentTurnActionCount));
        Player currentTurnPlayer = resolvePlayer(turnFlowService.currentTurnPlayerId);
        boolean overflowDiscardPhase = tp == TurnFlowService.TurnPhase.END_TURN;
        int overflowDiscardCount = !overflowDiscardPhase || currentTurnPlayer == null
                ? 0
                : Math.max(0, currentTurnPlayer.getHandCardCount() - TurnFlowService.MAX_HAND_SIZE);
        snap.setOverflowDiscardCount(overflowDiscardCount);
        snap.setRoundNumber(Math.max(1, fullRoundsCompleted + 1));
        snap.setDrawPileCount(engine.remainingCount());
        snap.setDiscardPileCount(engine.discardCount());
        Integer pendingPaymentAmt = null;
        if (tp == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            StackResponseState st = gameContext.getResponseState();
            if (st != null) {
                snap.setDecisionPlayerId(st.getAwaitingPlayerId());
                snap.setDecisionDeadlineEpochMs(st.getDeadlineEpochMs());
                snap.setPendingResponsePlayerId(st.getAwaitingPlayerId());
                snap.setPendingResponseRole(st.getRole().name());
                snap.setResponseDeadlineEpochMs(st.getDeadlineEpochMs());
                snap.setPendingResponseHint(
                        EffectStackOrchestrator.buildPendingResponseHint(st));
                snap.setPendingResponseContext(buildPendingResponseContext(st));
                if (st.getRole() == StackResponseState.Role.TENANT) {
                    EffectStackEntry top = gameContext.peekTopEffect();
                    if (top != null && top.isRentLike()) {
                        pendingPaymentAmt = top.getAmountDue();
                    }
                }
                snap.setDecisionKind(decisionKindForResponse(st, pendingPaymentAmt));
                snap.setDecisionLabel(decisionLabelForResponse(st, pendingPaymentAmt));
            }
            snap.setEffectStackDepth(gameContext.getEffectStackView().size());
        } else {
            snap.setDecisionPlayerId(turnFlowService.currentTurnPlayerId);
            snap.setDecisionKind(decisionKindForTurnPhase(tp));
            snap.setDecisionLabel(decisionLabelForTurnPhase(tp));
            snap.setDecisionDeadlineEpochMs(0L);
            snap.setPendingResponsePlayerId(null);
            snap.setPendingResponseRole(null);
            snap.setResponseDeadlineEpochMs(0L);
            snap.setPendingResponseHint(null);
            snap.setPendingResponseContext(null);
            snap.setEffectStackDepth(0);
        }
        snap.setPendingPaymentAmountM(pendingPaymentAmt);
        snap.setLastErrorCode(lastErrorCode);
        snap.setLastErrorMessage(lastErrorMessage);
        snap.setLastErrorTimestampEpochMs(lastErrorTimestampEpochMs);
        snap.setGameOver(sessionForceEnded || sessionEndedNaturally || "GAME_OVER".equals(originalPhase));
        snap.setForceEndReason(sessionForceEnded ? forceEndReason : null);
        if (playedCard != null) {
            long seq = ++playEventSequence;
            String actorId = playedBy != null ? playedBy.getPlayerId() : turnFlowService.currentTurnPlayerId;
            snap.setLastPlayedSequence(seq);
            snap.setLastPlayedPlayerId(actorId);
            snap.setLastPlayedActionType(
                    playedActionType != null && !playedActionType.isBlank()
                            ? playedActionType.trim().toUpperCase(Locale.ROOT)
                            : (phase != null ? phase : ""));
            snap.setLastPlayedCard(HandCardJson.toHandCardObject(playedCard));
        }
        for (Player p : sessionPlayers) {
            JsonArray bankArr = new JsonArray();
            for (Card c : p.getBankCardsView()) {
                if (c != null) {
                    bankArr.add(HandCardJson.toHandCardObject(c));
                }
            }
            JsonArray propArr = new JsonArray();
            for (PropertyCard pc : p.getPropertyCardsView()) {
                if (pc != null) {
                    propArr.add(HandCardJson.toHandCardObject(pc));
                }
            }
            List<PropertyColorProgress> prog =
                    PropertyZoneSummary.colorProgress(p.getPropertyCardsView());
            snap.addPlayerSummary(
                    p.getPlayerId(),
                    p.getDisplayName(),
                    p.getHandCardCount(),
                    p.getBankCardCount(),
                    p.getPropertyCardCount(),
                    p.getActionZoneCardCount(),
                    p.countCompletePropertySets(),
                    p.totalBankValueM(),
                    PropertyZoneSummary.summarizeByColor(p.getPropertyCardsView()),
                    bankArr,
                    propArr,
                    prog
            );
        }
        gameContext.getAiHistoryTracker().recordSnapshot(
                snap.getStateSequence(),
                snap.getRoundNumber(),
                snap.getPhase(),
                snap.getCurrentPlayerId(),
                snap.getLastActionSummary(),
                playedBy,
                playedCard,
                playedActionType,
                sessionPlayers);
        gameUpdateSubject.notifyStateChanged(snap);
    }

    private JsonObject buildPendingResponseContext(StackResponseState st) {
        if (st == null) {
            return null;
        }
        EffectStackEntry entry = responseEntryForContext(st);
        JsonObject o = new JsonObject();
        o.addProperty("role", st.getRole().name());
        if (entry != null) {
            o.addProperty("kind", entry.getKind().name());
            addIfPresent(o, "actorPlayerId", entry.getActorPlayerId());
            addIfPresent(o, "actorName", displayNameFor(entry.getActorPlayerId()));
            String actionEffectCode = inferredActionEffectCode(entry);
            String targetPlayerId = entry.getTenantPlayerId();
            if (entry.getKind() == EffectStackEntry.Kind.WAIVER) {
                EffectStackEntry targetEntry = findEffectEntryById(entry.getWaiverTargetEntryId());
                if (targetEntry != null) {
                    targetPlayerId = targetEntry.getActorPlayerId();
                }
                addIfPresent(o, "actionCardName", "Just Say No");
                addIfPresent(o, "actionEffectCode", "RENT_WAIVER");
            }
            addIfPresent(o, "targetPlayerId", targetPlayerId);
            addIfPresent(o, "targetName", displayNameFor(targetPlayerId));
            addIfPresent(o, "colorKey", entry.getColorKey());
            if (entry.getKind() != EffectStackEntry.Kind.WAIVER) {
                addIfPresent(o, "actionCardName", entry.getActionCardName());
                addIfPresent(o, "actionEffectCode", actionEffectCode);
            }
            if (entry.getAmountDue() > 0) {
                o.addProperty("amountDueM", entry.getAmountDue());
            }
        }
        if (st.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            EffectStackEntry bottom = bottomActionOrRentEntry();
            if (bottom != null && bottom != entry) {
                addIfPresent(o, "originalActorPlayerId", bottom.getActorPlayerId());
                addIfPresent(o, "originalActorName", displayNameFor(bottom.getActorPlayerId()));
                addIfPresent(o, "originalTargetPlayerId", bottom.getTenantPlayerId());
                addIfPresent(o, "originalTargetName", displayNameFor(bottom.getTenantPlayerId()));
                addIfPresent(o, "originalActionCardName", bottom.getActionCardName());
                addIfPresent(o, "originalActionEffectCode", inferredActionEffectCode(bottom));
                addIfPresent(o, "originalColorKey", bottom.getColorKey());
                if (bottom.getAmountDue() > 0) {
                    o.addProperty("originalAmountDueM", bottom.getAmountDue());
                }
            }
        }
        return o.size() == 1 && o.has("role") ? null : o;
    }

    private EffectStackEntry responseEntryForContext(StackResponseState st) {
        if (st.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            return gameContext.peekTopEffect();
        }
        EffectStackEntry top = gameContext.peekTopEffect();
        if (top != null && (top.isRentLike() || top.isActionLike())) {
            return top;
        }
        return bottomActionOrRentEntry();
    }

    private EffectStackEntry bottomActionOrRentEntry() {
        for (EffectStackEntry entry : gameContext.getEffectStackView()) {
            if (entry != null && (entry.isRentLike() || entry.isActionLike())) {
                return entry;
            }
        }
        return null;
    }

    private EffectStackEntry findEffectEntryById(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return null;
        }
        for (EffectStackEntry entry : gameContext.getEffectStackView()) {
            if (entry != null && entryId.equals(entry.getId())) {
                return entry;
            }
        }
        return null;
    }

    private static String inferredActionEffectCode(EffectStackEntry entry) {
        if (entry == null) {
            return null;
        }
        String explicit = entry.getActionEffectCode();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (!entry.isRentLike()) {
            return null;
        }
        String colorKey = entry.getColorKey();
        if ("DEBT_COLLECTOR".equals(colorKey) || "BIRTHDAY".equals(colorKey)) {
            return colorKey;
        }
        return "RENT";
    }

    private String displayNameFor(String playerId) {
        Player p = resolvePlayer(playerId);
        return p != null ? p.getDisplayName() : null;
    }

    private static void addIfPresent(JsonObject o, String key, String value) {
        if (value != null && !value.isBlank()) {
            o.addProperty(key, value);
        }
    }

    // --- private helpers ---

    private void recordErrorAndSnapshot(RuntimeException e) {
        recordError(e.getClass().getSimpleName(), runtimeExceptionDetail(e));
        pushSnapshot(currentSessionId,
                sessionForceEnded ? "GAME_FORCE_END" : "RULE_VIOLATION");
    }

    private String fallbackActionSummary(String phase) {
        if (phase == null) {
            return "";
        }
        return switch (phase) {
            case "INIT" -> "State refreshed.";
            case "DRAW" -> "Draw phase updated.";
            case "DEPLOY", "DEPOSIT", "ACTION", "DISCARD" -> "A play action completed.";
            case "TURN_END" -> "Turn ended.";
            case "FORCE_DISCARD_REQUIRED" -> "Discard down to 7 cards before ending turn.";
            case "GAME_OVER" -> "Game over.";
            case "REASSIGN_WILD" -> "Wild property color changes are not allowed.";
            case "RENT_PAID", "RENT_FAILED" -> "Rent settlement updated.";
            case "PAUSE_PENDING" -> "Pause vote in progress.";
            case "RULE_VIOLATION" -> (lastErrorMessage != null && !lastErrorMessage.isBlank())
                    ? lastErrorMessage
                    : "Rule violation.";
            case "GAME_FORCE_END" -> forceEndReason != null
                    ? "Game ended: " + forceEndReason : "Game force-ended.";
            case "ACTION_SUCCESS", "ACTION_FAILED", "ACTION_COUNTERED" ->
                    "Action card effect resolved.";
            case "RENT_AWAITING_RESPONSE" -> "Awaiting rent response.";
            case "JSN_AWAITING_COUNTER" -> "Awaiting landlord counter to Just Say No.";
            case "RESPONSE_PASS", "RESPONSE_TIMEOUT" -> "Rent response chain updated.";
            default -> phase.replace('_', ' ');
        };
    }

    private static String decisionKindForTurnPhase(TurnFlowService.TurnPhase phase) {
        if (phase == null) {
            return "UNKNOWN";
        }
        return switch (phase) {
            case DRAW -> "DRAW";
            case PLAY -> "PLAY";
            case END_TURN -> "END_TURN";
            case WAITING_FOR_RESPONSE -> "RESPONSE";
        };
    }

    private static String decisionLabelForTurnPhase(TurnFlowService.TurnPhase phase) {
        if (phase == null) {
            return "等待状态更新";
        }
        return switch (phase) {
            case DRAW -> "摸牌";
            case PLAY -> "出牌";
            case END_TURN -> "结束回合";
            case WAITING_FOR_RESPONSE -> "响应";
        };
    }

    private static String decisionKindForResponse(StackResponseState st, Integer pendingPaymentAmt) {
        if (st == null) {
            return "RESPONSE";
        }
        if (st.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            return "JUST_SAY_NO_COUNTER";
        }
        if (pendingPaymentAmt != null && pendingPaymentAmt > 0) {
            return "PAY_OR_JUST_SAY_NO";
        }
        return "JUST_SAY_NO_RESPONSE";
    }

    private static String decisionLabelForResponse(StackResponseState st, Integer pendingPaymentAmt) {
        if (st == null) {
            return "等待响应";
        }
        if (st.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            return "反制 Just Say No";
        }
        if (pendingPaymentAmt != null && pendingPaymentAmt > 0) {
            return "付款或 Just Say No";
        }
        return "接受或 Just Say No";
    }

    private void maybeAutosaveAfterFullRound() {
        saveLoadService.maybeAutosaveAfterFullRound(fullRoundsCompleted);
    }

    private static long sessionLimitMs() {
        Long v = Long.getLong(GameConstants.SESSION_LIMIT_MS_PROPERTY);
        return v != null ? v : GameConstants.DEFAULT_SESSION_LIMIT_MS;
    }

    private static String runtimeExceptionDetail(RuntimeException e) {
        String m = e.getMessage();
        if (m != null && !m.isBlank()) {
            return m;
        }
        return e.getClass().getSimpleName();
    }
}
