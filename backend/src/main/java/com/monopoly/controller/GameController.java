package com.monopoly.controller;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameConstants;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.card.ActionCard;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import com.monopoly.persistence.GameSessionMemento;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * [Facade]
 * Single entry point for the JavaFX client and WebSocket layer; hides turn flow and effect-stack details.
 * <p>
 * Core logic is delegated to single-responsibility collaborators:
 * <ul>
 *   <li>ClientCommandHandler — client command validation and dispatch</li>
 *   <li>TurnFlowService — turn flow (draw, play, discard, end turn, action cards)</li>
 *   <li>EffectStackOrchestrator — effect stack and response timers</li>
 *   <li>AiTurnService — AI turn execution</li>
 *   <li>RentSettlementService — rent settlement</li>
 *   <li>PauseVoteService — pause and PVP voting</li>
 *   <li>SaveLoadService — save, load, autosave</li>
 *   <li>SessionFactory — new-session deck shuffle, opening deal, initialization</li>
 *   <li>SeatAssembler — HVM/PVP/LLM/CUSTOM player and AI seat construction</li>
 *   <li>SnapshotBuilder — state/snapshot broadcast assembly</li>
 * </ul>
 */
public class GameController implements AiGameBridge {

    /*
     * Concurrency: a session is mutated by three threads — WebSocket worker threads
     * (client commands), the effect-response timeout thread, and the AI decision thread.
     * Each session owns exactly one GameController instance, so this instance doubles as
     * the per-session monitor: all network-facing command entry points are synchronized
     * on {@code this}, and the timeout/AI background tasks enter via {@code synchronized
     * (controller)} (see EffectStackOrchestrator / AiTurnService). Intrinsic locks are
     * reentrant, so nested service calls on the same thread are safe.
     */

    private static final Logger LOG = Logger.getLogger(GameController.class.getName());
    private static final boolean VERIFY_DECK = Boolean.parseBoolean(
            System.getProperty("monopoly.verifyDeck", "false"));
    private static final boolean TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO =
            Boolean.parseBoolean(System.getProperty(
                    "monopoly.search.trace.includeMemento",
                    "false"));

    private final GameEngineSingleton engine;
    private final TurnManager turnManager = new TurnManager();
    private final GameUpdateSubject gameUpdateSubject;

    /* --- six services --- */
    private final TurnFlowService turnFlowService;
    private final EffectStackOrchestrator effectStackOrchestrator;
    private final AiTurnService aiTurnService;
    private final SaveLoadService saveLoadService;
    private final PauseVoteService pauseVoteService;
    private final RentSettlementService rentSettlementService;
    private final SnapshotBuilder snapshotBuilder;
    private final SeatAssembler seatAssembler;
    private final SessionFactory sessionFactory;
    private final ClientCommandHandler clientCommandHandler;

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
    private volatile boolean suppressAiAutoContinuation;

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
        this.snapshotBuilder = new SnapshotBuilder(this, turnFlowService);
        this.seatAssembler = new SeatAssembler();
        this.sessionFactory = new SessionFactory(this, seatAssembler);
        this.clientCommandHandler = new ClientCommandHandler(this);
        clearLastError();
    }

    /**
     * Test/simulation hook. Normal WebSocket sessions keep using DeepSeekAiPlayStrategy.
     */
    public void setLlmAiStrategyFactory(IntFunction<AiPlayStrategy> llmAiStrategyFactory) {
        seatAssembler.setLlmAiStrategyFactory(llmAiStrategyFactory);
    }

    public void setLookaheadAiStrategyFactory(IntFunction<AiPlayStrategy> lookaheadAiStrategyFactory) {
        seatAssembler.setLookaheadAiStrategyFactory(lookaheadAiStrategyFactory);
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
    public synchronized void startNewSession(StartSessionRequest req) {
        sessionFactory.startNewSession(req);
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

    public synchronized void handleReassignWildCommand(String wildPropertyCardId, String newColorKey) {
        turnFlowService.reassignWildProperty(requireCurrentPlayer(), wildPropertyCardId, newColorKey);
    }

    public synchronized void endTurn(Player player) {
        if (player == null) {
            return;
        }
        Player next = turnFlowService.endTurn(player);
        runAiTurnIfNeeded(next);
    }

    void resumeAiTurnIfNeeded() {
        if (suppressAiAutoContinuation) {
            return;
        }
        if (turnFlowService.phase() == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
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

    public synchronized void handleDrawCommand(int count) {
        clientCommandHandler.handleDrawCommand(count);
    }

    public synchronized void handlePlayActionRequest(PlayActionRequest req) {
        clientCommandHandler.handlePlayActionRequest(req);
    }

    /**
     * PLAY phase: legal targets/params for an action card (client sends PLAY after picking).
     */
    public synchronized ActionOptionsResult queryActionOptionsForHandCard(String playerId, String cardId) {
        return clientCommandHandler.queryActionOptionsForHandCard(playerId, cardId);
    }

    /**
     * PLAY phase: options for DEPOSIT/DEPLOY/DISCARD/ACTION (PLAY_OPTIONS wizard).
     */
    public synchronized ActionOptionsResult queryPlayOptions(String playerId, String cardId, String actionType) {
        return clientCommandHandler.queryPlayOptions(playerId, cardId, actionType);
    }

    public void validatePlayActionRequest(PlayActionRequest req) {
        clientCommandHandler.validatePlayActionRequest(req);
    }

    public void handlePlayCommand(int handIndex, String actionType) {
        clientCommandHandler.handlePlayCommand(handIndex, actionType);
    }

    public synchronized void handleEndTurnCommand() {
        clientCommandHandler.handleEndTurnCommand();
    }

    public synchronized void handleQuitCommand(String playerId) {
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
        long humanTotal = sessionPlayers.stream()
                .filter(p -> p instanceof com.monopoly.model.player.HumanPlayer)
                .count();
        long humanQuit = sessionPlayers.stream()
                .filter(p -> p instanceof com.monopoly.model.player.HumanPlayer)
                .filter(p -> quitPlayerIds.contains(p.getPlayerId()))
                .count();
        if (humanTotal > 0 && humanQuit >= humanTotal) {
            sessionForceEnded = true;
            forceEndReason = "ALL_QUIT";
            pushSnapshot(currentSessionId, "GAME_FORCE_END");
        }
    }

    public synchronized void forceEndSession(String reason) {
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

    public synchronized void handleResponsePass(String actingPlayerId) {
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

    void resetRuntimeForNewSession(String sessionId, String mode) {
        this.currentSessionId = (sessionId == null || sessionId.isBlank())
                ? "session-default"
                : sessionId;
        this.sessionGameMode = (mode == null || mode.isBlank())
                ? "HVM"
                : mode.trim().toUpperCase(Locale.ROOT);
        resetPauseVote();
        this.fullRoundsCompleted = 0;
        this.sessionStartEpochMs = System.currentTimeMillis();
        this.sessionForceEnded = false;
        this.sessionEndedNaturally = false;
        this.suppressAiAutoContinuation = false;
        this.forceEndReason = null;
        this.quitPlayerIds.clear();
        this.snapshotBuilder.reset();
    }

    void installPlayers(List<Player> players) {
        sessionPlayers.clear();
        if (players != null) {
            sessionPlayers.addAll(players);
        }
        turnManager.bindTurnOrder(sessionPlayers);
        gameContext.bindPlayers(sessionPlayers);
        gameContext.clearEffectStack();
        gameContext.clearPendingDoubleRent();
        gameContext.resetAiHistory();
        cancelPendingResponseTimeout();
    }

    void cancelPendingResponseTimeout() {
        effectStackOrchestrator.cancelPendingResponseTimeout();
    }

    void resetPauseVote() {
        pauseVoteService.reset();
    }

    void runAiTurnIfNeeded(Player current) {
        if (!suppressAiAutoContinuation && current instanceof AIPlayer ai) {
            aiTurnService.executeAiTurn(ai);
        }
    }

    public synchronized void pause() {
        pauseVoteService.pause();
    }

    public synchronized void requestPause() {
        pauseVoteService.requestPause();
    }

    public synchronized void acknowledgePause(String playerId) {
        pauseVoteService.acknowledgePause(playerId);
    }

    public synchronized void resume() {
        pauseVoteService.resume();
        resumeAiTurnIfNeeded();
    }

    // ═══════════════════════════════════════════════════════
    //  Save / load (SaveLoadService)
    // ═══════════════════════════════════════════════════════

    public synchronized String exportSessionJson() {
        return saveLoadService.exportSessionJson();
    }

    public synchronized void importSessionJson(String json) {
        saveLoadService.importSessionJson(json);
    }

    public synchronized void pushCurrentState(String phase, String actionSummary) {
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

    GameUpdateSubject gameUpdateSubject() {
        return gameUpdateSubject;
    }

    TurnFlowService turnFlowService() {
        return turnFlowService;
    }

    EffectStackOrchestrator effectStackOrchestrator() {
        return effectStackOrchestrator;
    }

    String getCurrentSessionId() {
        return currentSessionId;
    }

    public String getCurrentSessionIdPublic() {
        return currentSessionId;
    }

    String getCurrentTurnPlayerId() {
        return turnFlowService.currentTurnPlayerId();
    }

    Player requireCurrentPlayer() {
        Player current = turnManager.getCurrentPlayer();
        if (current == null) {
            throw new IllegalStateException("No active player; call startNewSession first.");
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
        TurnFlowService.TurnPhase phase = turnFlowService.phase();
        gameContext.setTurnState(
                turnFlowService.currentTurnPlayerId(),
                phase == null ? "UNKNOWN" : phase.name(),
                Math.max(1, fullRoundsCompleted + 1),
                turnFlowService.actionCount(),
                TurnFlowService.MAX_ACTIONS_PER_TURN);
        gameContext.setStateSequence(snapshotBuilder.currentStateSequence());
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

    boolean isSessionEndedNaturally() {
        return sessionEndedNaturally;
    }

    String forceEndReason() {
        return forceEndReason;
    }

    long sessionStartEpochMs() {
        return sessionStartEpochMs;
    }

    String lastErrorCode() {
        return lastErrorCode;
    }

    String lastErrorMessage() {
        return lastErrorMessage;
    }

    long lastErrorTimestampEpochMs() {
        return lastErrorTimestampEpochMs;
    }

    void markTimeoutForceEnd() {
        this.sessionForceEnded = true;
        this.forceEndReason = "TIMEOUT";
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
            throw new IllegalStateException("Session has ended naturally; no further actions allowed.");
        }
        if (sessionForceEnded) {
            throw new IllegalStateException("Session has been force-ended; no further actions allowed.");
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
        throw new IllegalStateException("Session exceeded time limit; force-ended.");
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
        this.snapshotBuilder.reset();
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
        snapshotBuilder.broadcast(sessionId, phase, actionSummary, playedBy, playedCard, playedActionType);
    }

    // --- private helpers ---

    void recordErrorAndSnapshot(RuntimeException e) {
        recordError(e.getClass().getSimpleName(), runtimeExceptionDetail(e));
        pushSnapshot(currentSessionId,
                sessionForceEnded ? "GAME_FORCE_END" : "RULE_VIOLATION");
    }

    private void maybeAutosaveAfterFullRound() {
        saveLoadService.maybeAutosaveAfterFullRound(fullRoundsCompleted);
    }

    static long sessionLimitMs() {
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
